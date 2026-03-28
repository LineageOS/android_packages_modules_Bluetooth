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

package com.android.bluetooth.a2dpsink;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test cases for {@link A2dpSinkService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class A2dpSinkServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private A2dpSinkNativeInterface mNativeInterface;

    private static final int TEST_SAMPLE_RATE = 44;
    private static final int TEST_CHANNEL_COUNT = 1;

    private final BluetoothDevice mDevice1 = getTestDevice(83);
    private final BluetoothDevice mDevice2 = getTestDevice(82);

    private TestLooper mLooper;
    private A2dpSinkService mService;

    // Don't use @Before because the initTest and the test would be running on different thread.
    // This creates issues with the TestLooper, as it overrides Looper.myLooper for the current
    // thread only.
    public void initTest() {
        doReturn(Set.of(mDevice1, mDevice2)).when(mAdapterService).getBondedDevices();
        doReturn(1).when(mAdapterService).getMaxConnectedAudioDevices();
        TestUtils.mockGetSystemService(mAdapterService, AudioManager.class);

        doReturn(true).when(mNativeInterface).setActiveDevice(any());

        mLooper = new TestLooper();

        mService = new A2dpSinkService(mAdapterService, mNativeInterface, mLooper.getLooper());
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
    }

    private void syncHandler(int... what) {
        TestUtils.syncHandler(mLooper, what);
    }

    private void setupDeviceConnection(BluetoothDevice device) {
        assertThat(mLooper.nextMessage()).isNull();
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mLooper.nextMessage()).isNull();

        assertThat(mService.connect(device)).isTrue();
        syncHandler(0);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);
        mService.onConnectionStateChangedFromNative(device, STATE_CONNECTED);
        syncHandler(0);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);
    }

    /**
     * Mock the priority of a bluetooth device
     *
     * @param device - The bluetooth device you wish to mock the priority of
     * @param priority - The priority value you want the device to have
     */
    private void mockDevicePriority(BluetoothDevice device, int priority) {
        doReturn(priority)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.A2DP_SINK);
    }

    /** Test that initialization of the service completes and that we can get a instance */
    @Test
    public void testInitialize() {
        initTest();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that asking to connect with a null device fails */
    @Test
    public void testConnectNullDevice() {
        initTest();
        assertThat(mService.connect(null)).isEqualTo(false);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that a CONNECTION_POLICY_ALLOWED device can connected */
    @Test
    public void testConnectPolicyAllowedDevice() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that a CONNECTION_POLICY_FORBIDDEN device is not allowed to connect */
    @Test
    public void testConnectPolicyForbiddenDevice() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_FORBIDDEN);
        assertThat(mService.connect(mDevice1)).isFalse();
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that a CONNECTION_POLICY_UNKNOWN device is allowed to connect */
    @Test
    public void testConnectPolicyUnknownDevice() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_UNKNOWN);
        setupDeviceConnection(mDevice1);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that we can connect multiple devices */
    @Test
    public void testConnectMultipleDevices() {
        initTest();
        doReturn(5).when(mAdapterService).getMaxConnectedAudioDevices();

        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        mockDevicePriority(mDevice2, CONNECTION_POLICY_ALLOWED);

        setupDeviceConnection(mDevice1);
        setupDeviceConnection(mDevice2);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test to make sure we can disconnect a connected device */
    @Test
    public void testDisconnect() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        assertThat(mService.disconnect(mDevice1)).isTrue();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_DISCONNECTING);
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_DISCONNECTED);
        mLooper.dispatchAll();

        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Assure disconnect() fails with a device that's not connected */
    @Test
    public void testDisconnectDeviceDoesNotExist() {
        initTest();
        assertThat(mService.disconnect(mDevice1)).isFalse();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Assure disconnect() fails with an invalid device */
    @Test
    public void testDisconnectNullDevice() {
        initTest();
        assertThat(mService.disconnect(null)).isEqualTo(false);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Assure dump() returns something and does not crash */
    @Test
    public void testDump() {
        initTest();
        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
        assertThat(sb.toString()).isNotNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test that we can set the active device to a valid device and receive it back from
     * GetActiveDevice()
     */
    @Test
    public void testSetActiveDevice() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        assertThat(mService.getActiveDevice()).isNotEqualTo(mDevice1);
        assertThat(mService.setActiveDevice(mDevice1)).isTrue();
        assertThat(mService.getActiveDevice()).isEqualTo(mDevice1);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that calls to set a null active device succeed in unsetting the active device */
    @Test
    public void testSetActiveDeviceNullDevice() {
        initTest();
        assertThat(mService.setActiveDevice(null)).isTrue();
        assertThat(mService.getActiveDevice()).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Make sure we can receive the set audio configuration */
    @Test
    public void testGetAudioConfiguration() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        mService.onAudioConfigChangedFromNative(mDevice1, TEST_SAMPLE_RATE, TEST_CHANNEL_COUNT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_AUDIO_CONFIG_CHANGED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Make sure we ignore audio configuration changes for disconnected/unknown devices */
    @Test
    public void testOnAudioConfigChanged_withNullDevice_eventDropped() {
        initTest();
        mService.onAudioConfigChangedFromNative(null, TEST_SAMPLE_RATE, TEST_CHANNEL_COUNT);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Make sure we ignore audio configuration changes for disconnected/unknown devices */
    @Test
    public void testOnAudioConfigChanged_withUnknownDevice_eventDropped() {
        initTest();
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        mService.onAudioConfigChangedFromNative(mDevice1, TEST_SAMPLE_RATE, TEST_CHANNEL_COUNT);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Getting an audio config for a device that hasn't received one yet should return null */
    @Test
    public void testGetAudioConfigWithConfigUnset() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Getting an audio config for a null device should return null */
    @Test
    public void testGetAudioConfigNullDevice() {
        initTest();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that a newly connected device ends up in the set returned by getConnectedDevices */
    @Test
    public void testGetConnectedDevices() {
        initTest();
        ArrayList<BluetoothDevice> expected = new ArrayList<BluetoothDevice>();
        expected.add(mDevice1);

        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        assertThat(devices).isEqualTo(expected);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test that a newly connected device ends up in the set returned by
     * testGetDevicesMatchingConnectionStates
     */
    @Test
    public void testGetDevicesMatchingConnectionStatesConnected() {
        initTest();
        ArrayList<BluetoothDevice> expected = new ArrayList<BluetoothDevice>();
        expected.add(mDevice1);
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        List<BluetoothDevice> devices =
                mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED});
        assertThat(devices).isEqualTo(expected);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test that a all bonded device end up in the set returned by
     * testGetDevicesMatchingConnectionStates, even when they're disconnected
     */
    @Test
    public void testGetDevicesMatchingConnectionStatesDisconnected() {
        initTest();
        ArrayList<BluetoothDevice> expected = new ArrayList<BluetoothDevice>();
        expected.add(mDevice1);
        expected.add(mDevice2);

        List<BluetoothDevice> devices =
                mService.getDevicesMatchingConnectionStates(new int[] {STATE_DISCONNECTED});
        assertThat(devices).containsExactlyElementsIn(expected);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that GetConnectionPolicy() can get a device with policy "Allowed" */
    @Test
    public void testGetConnectionPolicyDeviceAllowed() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        assertThat(mService.getConnectionPolicy(mDevice1)).isEqualTo(CONNECTION_POLICY_ALLOWED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that GetConnectionPolicy() can get a device with policy "Forbidden" */
    @Test
    public void testGetConnectionPolicyDeviceForbidden() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_FORBIDDEN);
        assertThat(mService.getConnectionPolicy(mDevice1)).isEqualTo(CONNECTION_POLICY_FORBIDDEN);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that GetConnectionPolicy() can get a device with policy "Unknown" */
    @Test
    public void testGetConnectionPolicyDeviceUnknown() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_UNKNOWN);
        assertThat(mService.getConnectionPolicy(mDevice1)).isEqualTo(CONNECTION_POLICY_UNKNOWN);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that SetConnectionPolicy() can change a device's policy to "Allowed" */
    @Test
    public void testSetConnectionPolicyDeviceAllowed() {
        initTest();
        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP_SINK, CONNECTION_POLICY_ALLOWED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that SetConnectionPolicy() can change a device's policy to "Forbidden" */
    @Test
    public void testSetConnectionPolicyDeviceForbiddenWhileNotConnected() {
        initTest();
        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_FORBIDDEN)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP_SINK, CONNECTION_POLICY_FORBIDDEN);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test that SetConnectionPolicy() can change a connected device's policy to "Forbidden" and
     * that the new "Forbidden" policy causes a disconnect of the device.
     */
    @Test
    public void testSetConnectionPolicyDeviceForbiddenWhileConnected() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_FORBIDDEN)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP_SINK, CONNECTION_POLICY_FORBIDDEN);

        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        verify(mNativeInterface).disconnectA2dpSink(eq(mDevice1));
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_DISCONNECTING);
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_DISCONNECTED);
        mLooper.dispatchAll();

        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test that SetConnectionPolicy() can change a device's policy to "Unknown" */
    @Test
    public void testSetConnectionPolicyDeviceUnknown() {
        initTest();
        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_UNKNOWN)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP_SINK, CONNECTION_POLICY_UNKNOWN);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testDumpDoesNotCrash() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);
        setupDeviceConnection(mDevice1);

        mService.dump(new StringBuilder());
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * b/436924551 Test that the service can be reconnected immediately after a disconnection event
     * is handled and that the state machine is not spuriously cleaned up.
     */
    @Test
    public void testReconnection() {
        initTest();
        mockDevicePriority(mDevice1, CONNECTION_POLICY_ALLOWED);

        // Report and process connection event.
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_CONNECTED);
        syncHandler(0);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);

        // Report disconnection and simultaneously a re-connection event.
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_DISCONNECTED);
        mService.onConnectionStateChangedFromNative(mDevice1, STATE_CONNECTED);

        // Process the disconnection event.
        // This generates a CLEANUP message, scheduled after the re-connection event.
        syncHandler(0);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);

        // Process the re-connection event.
        // The CLEANUP message is discarded.
        syncHandler(0);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mLooper.nextMessage()).isNull();
    }
}
