/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.hfpclient.HeadsetClientService.MAX_HFP_SCO_VOICE_CALL_VOLUME;
import static com.android.bluetooth.hfpclient.HeadsetClientService.MIN_HFP_SCO_VOICE_CALL_VOLUME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.BatteryManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link HeadsetClientService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private HeadsetClientStateMachine mStateMachine;
    @Mock private HeadsetClientNativeInterface mNativeInterface;
    @Mock private RemoteDevices mRemoteDevices;
    @Mock private PackageManager mPackageManager;

    private HeadsetClientService mService;

    private static final int STANDARD_WAIT_MILLIS = 1000;
    private static final int SERVICE_START_WAIT_MILLIS = 100;

    private AudioManager mMockAudioManager;

    @Before
    public void setUp() throws Exception {
        mMockAudioManager = mockGetSystemService(mAdapterService, AudioManager.class);
        mockGetSystemService(mAdapterService, BatteryManager.class);
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
    }

    @Test
    public void testInitialize() throws Exception {
        startService();
    }

    @Ignore("b/260202548")
    @Test
    public void testSendBIEVtoStateMachineWhenBatteryChanged() throws Exception {
        startService();

        // Put mock state machine
        BluetoothDevice device = getTestDevice(3);
        mService.getStateMachineMap().put(device, mStateMachine);

        // Send battery changed intent
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 50);
        mService.sendBroadcast(intent);

        // Expect send BIEV to state machine
        verify(mStateMachine, timeout(STANDARD_WAIT_MILLIS).times(1))
                .sendMessage(eq(HeadsetClientStateMachine.SEND_BIEV), eq(2), anyInt());
    }

    @Test
    public void testUpdateBatteryLevel() throws Exception {
        startService();

        // Adding a wait to prevent potential failure caused by delayed broadcast intent.
        TimeUnit.MILLISECONDS.sleep(SERVICE_START_WAIT_MILLIS);
        // Put mock state machine
        BluetoothDevice device = getTestDevice(3);
        mService.getStateMachineMap().put(device, mStateMachine);

        mService.updateBatteryLevel();

        // Expect send BIEV to state machine
        verify(mStateMachine, timeout(STANDARD_WAIT_MILLIS).times(1))
                .sendMessage(eq(HeadsetClientStateMachine.SEND_BIEV), eq(2), anyInt());
    }

    @Test
    public void testSetCallAudioPolicy() throws Exception {
        startService();

        // Put mock state machine
        BluetoothDevice device = getTestDevice(3);
        mService.getStateMachineMap().put(device, mStateMachine);

        mService.setAudioPolicy(device, new BluetoothSinkAudioPolicy.Builder().build());

        verify(mStateMachine, timeout(STANDARD_WAIT_MILLIS).times(1))
                .setAudioPolicy(any(BluetoothSinkAudioPolicy.class));
    }

    @Test
    public void testDumpDoesNotCrash() throws Exception {
        startService();

        // Put mock state machine
        BluetoothDevice device = getTestDevice(3);
        mService.getStateMachineMap().put(device, mStateMachine);

        mService.dump(new StringBuilder());
    }

    @Test
    public void testHfpClientConnectionServiceStarted() throws Exception {
        doReturn(false).when(mPackageManager).hasSystemFeature(FEATURE_WATCH);

        HeadsetClientService service = new HeadsetClientService(mAdapterService, mNativeInterface);

        verify(mAdapterService).startService(any(Intent.class));

        service.cleanup();
    }

    @Test
    public void testHfpClientConnectionServiceNotStarted_wearable() throws Exception {
        doReturn(true).when(mPackageManager).hasSystemFeature(FEATURE_WATCH);

        HeadsetClientService service = new HeadsetClientService(mAdapterService, mNativeInterface);

        verify(mAdapterService, never()).startService(any(Intent.class));

        service.cleanup();
    }

    /**
     * Test AM to HF volume symmetric. The test takes the AM volume range 1-10 and HF 1-15. All the
     * AM values are mapped with corresponding HF values. After all collected, the test converts
     * back HF values and checks if they match AM. This proves that the conversion is symmetric.
     */
    @Test
    public void testAmHfVolumeSymmetric_AmLowerRange() {
        int amMin = 1;
        int amMax = 10;
        Map<Integer, Integer> amToHfMap = new HashMap<>();

        assertThat(amMax).isLessThan(MAX_HFP_SCO_VOICE_CALL_VOLUME);

        doReturn(amMax).when(mMockAudioManager).getStreamMaxVolume(anyInt());
        doReturn(amMin).when(mMockAudioManager).getStreamMinVolume(anyInt());

        HeadsetClientService service = new HeadsetClientService(mAdapterService, mNativeInterface);

        for (int i = amMin; i <= amMax; i++) {
            // Collect AM to HF conversion
            amToHfMap.put(i, service.amToHfVol(i));
        }

        for (Map.Entry entry : amToHfMap.entrySet()) {
            // Convert back from collected HF to AM and check if equal the saved AM value
            assertThat(service.hfToAmVol((int) entry.getValue())).isEqualTo(entry.getKey());
        }
    }

    /**
     * Test HF to AM volume symmetric. The test takes the AM volume range 1-20 and HF 1-15. All the
     * HF values are mapped with corresponding AM values. After all collected, the test converts
     * back AM values and checks if they match HF. This proves that the conversion is symmetric.
     */
    @Test
    public void testAmHfVolumeSymmetric_HfLowerRange() {
        int amMin = 1;
        int amMax = 20;
        Map<Integer, Integer> hfToAmMap = new HashMap<>();

        assertThat(amMax).isGreaterThan(MAX_HFP_SCO_VOICE_CALL_VOLUME);

        doReturn(amMax).when(mMockAudioManager).getStreamMaxVolume(anyInt());
        doReturn(amMin).when(mMockAudioManager).getStreamMinVolume(anyInt());

        HeadsetClientService service = new HeadsetClientService(mAdapterService, mNativeInterface);

        for (int i = MIN_HFP_SCO_VOICE_CALL_VOLUME; i <= MAX_HFP_SCO_VOICE_CALL_VOLUME; i++) {
            // Collect HF to AM conversion
            hfToAmMap.put(i, service.hfToAmVol(i));
        }

        for (Map.Entry entry : hfToAmMap.entrySet()) {
            // Convert back from collected AM to HF and check if equal the saved HF value
            assertThat(service.amToHfVol((int) entry.getValue())).isEqualTo(entry.getKey());
        }
    }

    /**
     * Test that {@link HeadsetClientService#getConnectedDevices()} returns an empty list when no
     * devices are being managed.
     */
    @Test
    public void getConnectedDevices_noDevices() throws Exception {
        startService();
        // No devices added to the state machine map
        assertThat(mService.getConnectedDevices()).isEmpty();
    }

    /**
     * Test that {@link HeadsetClientService#getConnectedDevices()} returns only the device that is
     * in the CONNECTED state.
     */
    @Test
    public void getConnectedDevices_oneConnectedDevice() throws Exception {
        startService();
        BluetoothDevice device = getTestDevice(0);
        HeadsetClientStateMachine sm = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm).getConnectionState();
        mService.getStateMachineMap().put(device, sm);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        assertThat(devices).containsExactly(device);
    }

    /**
     * Test that {@link HeadsetClientService#getConnectedDevices()} returns an empty list when a
     * device is not in the CONNECTED state.
     */
    @Test
    public void getConnectedDevices_oneDisconnectedDevice() throws Exception {
        startService();
        BluetoothDevice device = getTestDevice(0);
        HeadsetClientStateMachine sm = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_DISCONNECTED).when(sm).getConnectionState();
        mService.getStateMachineMap().put(device, sm);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        assertThat(devices).isEmpty();
    }

    /**
     * Test that {@link HeadsetClientService#getConnectedDevices()} returns only the devices that
     * are in the CONNECTED state from a list of devices in various states.
     */
    @Test
    public void getConnectedDevices_multipleDevices_mixedStates() throws Exception {
        startService();
        BluetoothDevice connectedDevice1 = getTestDevice(0);
        HeadsetClientStateMachine sm1 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm1).getConnectionState();
        mService.getStateMachineMap().put(connectedDevice1, sm1);

        BluetoothDevice connectingDevice = getTestDevice(1);
        HeadsetClientStateMachine sm2 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTING).when(sm2).getConnectionState();
        mService.getStateMachineMap().put(connectingDevice, sm2);

        BluetoothDevice disconnectedDevice = getTestDevice(2);
        HeadsetClientStateMachine sm3 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_DISCONNECTED).when(sm3).getConnectionState();
        mService.getStateMachineMap().put(disconnectedDevice, sm3);

        BluetoothDevice connectedDevice2 = getTestDevice(3);
        HeadsetClientStateMachine sm4 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm4).getConnectionState();
        mService.getStateMachineMap().put(connectedDevice2, sm4);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        assertThat(devices).containsExactly(connectedDevice1, connectedDevice2);
    }

    /**
     * Test that {@link HeadsetClientService#getDevicesMatchingConnectionStates(int[])} returns an
     * empty list when no devices are managed.
     */
    @Test
    public void getDevicesMatchingConnectionStates_noDevices() throws Exception {
        startService();
        int[] states = {STATE_CONNECTED, STATE_CONNECTING};
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();
    }

    /**
     * Test that {@link HeadsetClientService#getDevicesMatchingConnectionStates(int[])} returns an
     * empty list when the desired states array is empty.
     */
    @Test
    public void getDevicesMatchingConnectionStates_emptyStates() throws Exception {
        startService();
        BluetoothDevice device = getTestDevice(0);
        HeadsetClientStateMachine sm = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm).getConnectionState();
        mService.getStateMachineMap().put(device, sm);

        int[] states = {};
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();
    }

    /**
     * Test that {@link HeadsetClientService#getDevicesMatchingConnectionStates(int[])} returns
     * devices that match a single desired state.
     */
    @Test
    public void getDevicesMatchingConnectionStates_singleStateMatch() throws Exception {
        startService();
        BluetoothDevice connectedDevice = getTestDevice(0);
        HeadsetClientStateMachine sm1 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm1).getConnectionState();
        mService.getStateMachineMap().put(connectedDevice, sm1);

        BluetoothDevice connectingDevice = getTestDevice(1);
        HeadsetClientStateMachine sm2 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTING).when(sm2).getConnectionState();
        mService.getStateMachineMap().put(connectingDevice, sm2);

        int[] states = {STATE_CONNECTED};
        List<BluetoothDevice> devices = mService.getDevicesMatchingConnectionStates(states);
        assertThat(devices).containsExactly(connectedDevice);
    }

    /**
     * Test that {@link HeadsetClientService#getDevicesMatchingConnectionStates(int[])} returns all
     * devices that match any of the multiple desired states.
     */
    @Test
    public void getDevicesMatchingConnectionStates_multipleStatesMatch() throws Exception {
        startService();
        BluetoothDevice connectedDevice = getTestDevice(0);
        HeadsetClientStateMachine sm1 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm1).getConnectionState();
        mService.getStateMachineMap().put(connectedDevice, sm1);

        BluetoothDevice connectingDevice = getTestDevice(1);
        HeadsetClientStateMachine sm2 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTING).when(sm2).getConnectionState();
        mService.getStateMachineMap().put(connectingDevice, sm2);

        BluetoothDevice disconnectedDevice = getTestDevice(2);
        HeadsetClientStateMachine sm3 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_DISCONNECTED).when(sm3).getConnectionState();
        mService.getStateMachineMap().put(disconnectedDevice, sm3);

        int[] states = {STATE_CONNECTED, STATE_CONNECTING};
        List<BluetoothDevice> devices = mService.getDevicesMatchingConnectionStates(states);
        assertThat(devices).containsExactly(connectedDevice, connectingDevice);
    }

    /**
     * Test that {@link HeadsetClientService#getDevicesMatchingConnectionStates(int[])} returns an
     * empty list when no devices match the desired states.
     */
    @Test
    public void getDevicesMatchingConnectionStates_noMatch() throws Exception {
        startService();
        BluetoothDevice connectedDevice = getTestDevice(0);
        HeadsetClientStateMachine sm1 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTED).when(sm1).getConnectionState();
        mService.getStateMachineMap().put(connectedDevice, sm1);

        BluetoothDevice connectingDevice = getTestDevice(1);
        HeadsetClientStateMachine sm2 = Mockito.mock(HeadsetClientStateMachine.class);
        doReturn(STATE_CONNECTING).when(sm2).getConnectionState();
        mService.getStateMachineMap().put(connectingDevice, sm2);

        int[] states = {STATE_DISCONNECTED, STATE_DISCONNECTING};
        List<BluetoothDevice> devices = mService.getDevicesMatchingConnectionStates(states);
        assertThat(devices).isEmpty();
    }

    private void startService() throws Exception {
        mService = new HeadsetClientService(mAdapterService, mNativeInterface);
        mService.setAvailable(true);
    }
}
