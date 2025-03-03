/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.bluetooth.mapclient;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpMasRecord;
import android.content.Context;
import android.telephony.SubscriptionManager;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class MapClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private MnsService mMnsService;

    private final BluetoothAdapter mAdapter =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter();
    private final BluetoothDevice mRemoteDevice = getTestDevice(0);

    private MapClientService mService;
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        TestUtils.mockGetSystemService(
                mAdapterService, Context.TELEPHONY_SUBSCRIPTION_SERVICE, SubscriptionManager.class);

        mTestLooper = new TestLooper();

        mService = new MapClientService(mAdapterService, mTestLooper.getLooper(), mMnsService);
        mService.setAvailable(true);

        // Try getting the Bluetooth adapter
        assertThat(mAdapter).isNotNull();
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        assertThat(MapClientService.getMapClientService()).isNull();
    }

    @Test
    public void initialize() {
        assertThat(MapClientService.getMapClientService()).isNotNull();
    }

    @Test
    public void setMapClientService_withNull() {
        MapClientService.setMapClientService(null);

        assertThat(MapClientService.getMapClientService()).isNull();
    }

    @Test
    public void dump_callsStateMachineDump() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        StringBuilder builder = new StringBuilder();

        mService.dump(builder);

        verify(sm).dump(builder);
    }

    @Test
    public void setConnectionPolicy() {
        doReturn(true).when(mDatabaseManager).setProfileConnectionPolicy(any(), anyInt(), anyInt());

        assertThat(mService.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_UNKNOWN)).isTrue();
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mRemoteDevice, BluetoothProfile.MAP_CLIENT, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void getConnectionPolicy() {
        for (int policy :
                List.of(
                        CONNECTION_POLICY_UNKNOWN,
                        CONNECTION_POLICY_FORBIDDEN,
                        CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.getConnectionPolicy(mRemoteDevice)).isEqualTo(policy);
        }
    }

    @Test
    public void connect_whenPolicyIsForbidden_returnsFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        assertThat(mService.connect(mRemoteDevice)).isFalse();
    }

    @Test
    public void connect_whenPolicyIsAllowed_returnsTrue() {
        assertThat(mService.connect(mRemoteDevice)).isTrue();
    }

    @Test
    public void disconnect_whenNotConnected_returnsFalse() {
        assertThat(mService.disconnect(mRemoteDevice)).isFalse();
    }

    @Test
    public void disconnect_whenConnected_returnsTrue() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        when(sm.getState()).thenReturn(connectionState);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        assertThat(mService.disconnect(mRemoteDevice)).isTrue();

        verify(sm).disconnect();
    }

    @Test
    public void getConnectionState_whenNotConnected() {
        assertThat(mService.getConnectionState(mRemoteDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void getConnectionState_whenConnected() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        when(sm.getState()).thenReturn(connectionState);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        assertThat(mService.getConnectionState(mRemoteDevice)).isEqualTo(connectionState);
    }

    @Test
    public void getConnectedDevices() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        BluetoothDevice[] bondedDevices = new BluetoothDevice[] {mRemoteDevice};
        when(mAdapterService.getBondedDevices()).thenReturn(bondedDevices);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getState()).thenReturn(connectionState);

        assertThat(mService.getConnectedDevices()).contains(mRemoteDevice);
    }

    @Test
    public void getMceStateMachineForDevice() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        assertThat(mService.getMceStateMachineForDevice(mRemoteDevice)).isEqualTo(sm);
    }

    @Test
    public void getSupportedFeatures() {
        int supportedFeatures = 100;
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getSupportedFeatures()).thenReturn(supportedFeatures);

        assertThat(mService.getSupportedFeatures(mRemoteDevice)).isEqualTo(supportedFeatures);
        verify(sm).getSupportedFeatures();
    }

    @Test
    public void setMessageStatus() {
        String handle = "FFAB";
        int status = 123;
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.setMessageStatus(handle, status)).thenReturn(true);

        assertThat(mService.setMessageStatus(mRemoteDevice, handle, status)).isTrue();
        verify(sm).setMessageStatus(handle, status);
    }

    @Test
    public void getUnreadMessages() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getUnreadMessages()).thenReturn(true);

        assertThat(mService.getUnreadMessages(mRemoteDevice)).isTrue();
        verify(sm).getUnreadMessages();
    }

    @Test
    public void cleanUpDevice() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        mService.cleanupDevice(mRemoteDevice, sm);

        assertThat(mService.getInstanceMap()).doesNotContainKey(mRemoteDevice);
    }

    @Test
    public void cleanUpDevice_deviceExistsWithDifferentStateMachine_doesNotCleanUpDevice() {
        MceStateMachine sm1 = mock(MceStateMachine.class);
        MceStateMachine sm2 = mock(MceStateMachine.class);

        // Add device as state machine 1
        mService.getInstanceMap().put(mRemoteDevice, sm1);

        // Remove device as state machine 2
        mService.cleanupDevice(mRemoteDevice, sm2);

        // Device and state machine1 should still be there
        assertThat(mService.getInstanceMap()).containsKey(mRemoteDevice);
        assertThat(mService.getInstanceMap().get(mRemoteDevice)).isEqualTo(sm1);
    }

    @Test
    public void aclDisconnectedNoTransport_whenConnected_doesNotCallDisconnect() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getState()).thenReturn(connectionState);

        mService.aclDisconnected(mRemoteDevice, BluetoothDevice.ERROR);
        mTestLooper.dispatchAll();

        verify(sm, never()).disconnect();
    }

    @Test
    public void aclDisconnectedLeTransport_whenConnected_doesNotCallDisconnect() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getState()).thenReturn(connectionState);

        mService.aclDisconnected(mRemoteDevice, BluetoothDevice.TRANSPORT_LE);
        mTestLooper.dispatchAll();

        verify(sm, never()).disconnect();
    }

    @Test
    public void aclDisconnectedBrEdrTransport_whenConnected_callsDisconnect() {
        int connectionState = STATE_CONNECTED;
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        when(sm.getState()).thenReturn(connectionState);

        mService.aclDisconnected(mRemoteDevice, BluetoothDevice.TRANSPORT_BREDR);
        mTestLooper.dispatchAll();

        verify(sm).disconnect();
    }

    @Test
    public void receiveSdpRecord_receivedMasRecord_sdpSuccess() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);
        SdpMasRecord mockSdpRecord = mock(SdpMasRecord.class);

        mService.receiveSdpSearchRecord(
                mRemoteDevice, MceStateMachine.SDP_SUCCESS, mockSdpRecord, BluetoothUuid.MAS);
        mTestLooper.dispatchAll();

        verify(sm).sendSdpResult(eq(MceStateMachine.SDP_SUCCESS), eq(mockSdpRecord));
    }

    @Test
    public void receiveSdpRecord_withoutMasRecord_sdpFailed() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        mService.receiveSdpSearchRecord(
                mRemoteDevice, MceStateMachine.SDP_SUCCESS, null, BluetoothUuid.MAS);
        mTestLooper.dispatchAll();

        // Verify message: SDP was successfully complete, but no record was returned
        verify(sm).sendSdpResult(eq(MceStateMachine.SDP_SUCCESS), eq(null));
    }

    @Test
    public void receiveSdpRecord_withSdpBusy_sdpFailed() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        mService.receiveSdpSearchRecord(
                mRemoteDevice, MceStateMachine.SDP_BUSY, null, BluetoothUuid.MAS);
        mTestLooper.dispatchAll();

        // Verify message: SDP was busy and no record was returned
        verify(sm).sendSdpResult(eq(MceStateMachine.SDP_BUSY), eq(null));
    }

    @Test
    public void receiveSdpRecord_withSdpFailed_sdpFailed() {
        MceStateMachine sm = mock(MceStateMachine.class);
        mService.getInstanceMap().put(mRemoteDevice, sm);

        mService.receiveSdpSearchRecord(
                mRemoteDevice, MceStateMachine.SDP_FAILED, null, BluetoothUuid.MAS);
        mTestLooper.dispatchAll();

        // Verify message: SDP was failed for some reason and no record was returned
        verify(sm).sendSdpResult(eq(MceStateMachine.SDP_FAILED), eq(null));
    }

    @Test
    public void connectOneDevice_whenAllowed_isConnected() {
        assertThat(mService.getInstanceMap()).doesNotContainKey(mRemoteDevice);

        assertThat(mService.connect(mRemoteDevice)).isTrue();
        assertThat(mService.getInstanceMap().keySet()).containsExactly(mRemoteDevice);

        mTestLooper.dispatchAll();
        assertThat(mService.getConnectionState(mRemoteDevice)).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void connectDevice_whenMaxDevicesAreConnected_isRejected() {
        List<BluetoothDevice> list = new ArrayList<>();
        for (int i = 0; i < MapClientService.MAXIMUM_CONNECTED_DEVICES; ++i) {
            BluetoothDevice testDevice = getTestDevice(i);
            assertThat(mService.getInstanceMap().get(testDevice)).isNull();
            assertThat(mService.connect(testDevice)).isTrue();

            list.add(testDevice);
        }

        mTestLooper.dispatchAll();
        assertThat(mService.getInstanceMap().keySet()).containsExactlyElementsIn(list);

        // Try to connect one more device. Should fail.
        assertThat(mService.connect(getTestDevice(0xAF))).isFalse();
    }
}
