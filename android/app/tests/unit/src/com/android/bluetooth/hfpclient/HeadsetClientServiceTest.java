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

package com.android.bluetooth.hfpclient;

import static android.content.pm.PackageManager.FEATURE_WATCH;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.hfpclient.HeadsetClientService.MAX_HFP_SCO_VOICE_CALL_VOLUME;
import static com.android.bluetooth.hfpclient.HeadsetClientService.MIN_HFP_SCO_VOICE_CALL_VOLUME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.BatteryManager;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private HeadsetClientStateMachine mStateMachine;
    @Mock private NativeInterface mNativeInterface;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private RemoteDevices mRemoteDevices;

    private HeadsetClientService mService;
    private boolean mIsHeadsetClientServiceStarted;

    private static final int STANDARD_WAIT_MILLIS = 1000;
    private static final int SERVICE_START_WAIT_MILLIS = 100;

    private AudioManager mMockAudioManager;

    <T> T mockGetSystemService(String serviceName, Class<T> serviceClass) {
        return TestUtils.mockGetSystemService(mAdapterService, serviceName, serviceClass);
    }

    @Before
    public void setUp() throws Exception {
        mMockAudioManager = mockGetSystemService(Context.AUDIO_SERVICE, AudioManager.class);
        mockGetSystemService(Context.BATTERY_SERVICE, BatteryManager.class);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        NativeInterface.setInstance(mNativeInterface);
    }

    @After
    public void tearDown() throws Exception {
        NativeInterface.setInstance(null);
        stopServiceIfStarted();
    }

    @Test
    public void testInitialize() throws Exception {
        startService();
        assertThat(HeadsetClientService.getHeadsetClientService()).isNotNull();
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
        PackageManager packageManager = Mockito.mock(PackageManager.class);

        doReturn(false).when(packageManager).hasSystemFeature(FEATURE_WATCH);
        doReturn(packageManager).when(mAdapterService).getPackageManager();

        HeadsetClientService service = new HeadsetClientService(mAdapterService);

        verify(mAdapterService).startService(any(Intent.class));

        service.cleanup();
    }

    @Test
    public void testHfpClientConnectionServiceNotStarted_wearable() throws Exception {
        PackageManager packageManager = Mockito.mock(PackageManager.class);

        doReturn(true).when(packageManager).hasSystemFeature(FEATURE_WATCH);
        doReturn(packageManager).when(mAdapterService).getPackageManager();

        HeadsetClientService service = new HeadsetClientService(mAdapterService);

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

        HeadsetClientService service = new HeadsetClientService(mAdapterService);

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

        HeadsetClientService service = new HeadsetClientService(mAdapterService);

        for (int i = MIN_HFP_SCO_VOICE_CALL_VOLUME; i <= MAX_HFP_SCO_VOICE_CALL_VOLUME; i++) {
            // Collect HF to AM conversion
            hfToAmMap.put(i, service.hfToAmVol(i));
        }

        for (Map.Entry entry : hfToAmMap.entrySet()) {
            // Convert back from collected AM to HF and check if equal the saved HF value
            assertThat(service.amToHfVol((int) entry.getValue())).isEqualTo(entry.getKey());
        }
    }

    private void startService() throws Exception {
        mService = new HeadsetClientService(mAdapterService);
        mService.setAvailable(true);
        mIsHeadsetClientServiceStarted = true;
    }

    private void stopServiceIfStarted() throws Exception {
        if (mIsHeadsetClientServiceStarted) {
            mService.cleanup();
            assertThat(HeadsetClientService.getHeadsetClientService()).isNull();
        }
    }
}
