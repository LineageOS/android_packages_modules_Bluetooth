/*
 * Copyright 2019 The Android Open Source Project
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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hfp.HeadsetService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class SilenceDeviceManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;

    private final BluetoothDevice mDevice = getTestDevice(28);

    private SilenceDeviceManager mSilenceDeviceManager;
    private HandlerThread mHandlerThread;
    private Looper mLooper;
    private int mVerifyCount = 0;

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);
        when(mServiceFactory.getA2dpService()).thenReturn(mA2dpService);
        when(mServiceFactory.getHeadsetService()).thenReturn(mHeadsetService);

        mHandlerThread = new HandlerThread("SilenceManagerTestHandlerThread");
        mHandlerThread.start();
        mLooper = mHandlerThread.getLooper();
        mSilenceDeviceManager = new SilenceDeviceManager(mAdapterService, mServiceFactory, mLooper);
        mSilenceDeviceManager.start();
    }

    @After
    public void tearDown() throws Exception {
        mSilenceDeviceManager.cleanup();
        mHandlerThread.quit();
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void testSetGetDeviceSilence() {
        testSetGetDeviceSilenceConnectedCase(false, true);
        testSetGetDeviceSilenceConnectedCase(false, false);
        testSetGetDeviceSilenceConnectedCase(true, true);
        testSetGetDeviceSilenceConnectedCase(true, false);

        testSetGetDeviceSilenceDisconnectedCase(false);
        testSetGetDeviceSilenceDisconnectedCase(true);
    }

    void testSetGetDeviceSilenceConnectedCase(boolean wasSilenced, boolean enableSilence) {
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        doReturn(true).when(mA2dpService).setSilenceMode(mDevice, enableSilence);
        doReturn(true).when(mHeadsetService).setSilenceMode(mDevice, enableSilence);

        // Send A2DP/HFP connected intent
        a2dpConnected(mDevice);
        headsetConnected(mDevice);

        // Set pre-state for mSilenceDeviceManager
        if (wasSilenced) {
            assertThat(mSilenceDeviceManager.setSilenceMode(mDevice, true)).isTrue();
            TestUtils.waitForLooperToFinishScheduledTask(mLooper);
            verify(mAdapterService, times(++mVerifyCount))
                    .sendBroadcastAsUser(
                            intentArgument.capture(), eq(UserHandle.ALL),
                            eq(BLUETOOTH_CONNECT), any(Bundle.class));
        }

        // Set silence state and check whether state changed successfully
        assertThat(mSilenceDeviceManager.setSilenceMode(mDevice, enableSilence)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
        assertThat(mSilenceDeviceManager.getSilenceMode(mDevice)).isEqualTo(enableSilence);

        // Check for silence state changed intent
        if (wasSilenced != enableSilence) {
            verify(mAdapterService, times(++mVerifyCount))
                    .sendBroadcastAsUser(
                            intentArgument.capture(), eq(UserHandle.ALL),
                            eq(BLUETOOTH_CONNECT), any(Bundle.class));
            verifySilenceStateIntent(intentArgument.getValue());
        }

        // Remove test devices
        a2dpDisconnected(mDevice);
        headsetDisconnected(mDevice);

        assertThat(mSilenceDeviceManager.getSilenceMode(mDevice)).isFalse();
        if (enableSilence) {
            // If the silence mode is enabled, it should be automatically disabled
            // after device is disconnected.
            verify(mAdapterService, times(++mVerifyCount))
                    .sendBroadcastAsUser(
                            intentArgument.capture(), eq(UserHandle.ALL),
                            eq(BLUETOOTH_CONNECT), any(Bundle.class));
        }
    }

    void testSetGetDeviceSilenceDisconnectedCase(boolean enableSilence) {
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        // Set silence mode and it should stay disabled
        assertThat(mSilenceDeviceManager.setSilenceMode(mDevice, enableSilence)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
        assertThat(mSilenceDeviceManager.getSilenceMode(mDevice)).isFalse();

        // Should be no intent been broadcasted
        verify(mAdapterService, times(mVerifyCount))
                .sendBroadcastAsUser(
                        intentArgument.capture(), eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT), any(Bundle.class));
    }

    void verifySilenceStateIntent(Intent intent) {
        assertThat(intent.getAction()).isEqualTo(BluetoothDevice.ACTION_SILENCE_MODE_CHANGED);
        assertThat(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(mDevice);
    }

    /** Helper to indicate A2dp connected for a device. */
    private void a2dpConnected(BluetoothDevice device) {
        mSilenceDeviceManager.a2dpConnectionStateChanged(
                device, STATE_DISCONNECTED, STATE_CONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate A2dp disconnected for a device. */
    private void a2dpDisconnected(BluetoothDevice device) {
        mSilenceDeviceManager.a2dpConnectionStateChanged(
                device, STATE_CONNECTED, STATE_DISCONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate Headset connected for a device. */
    private void headsetConnected(BluetoothDevice device) {
        mSilenceDeviceManager.hfpConnectionStateChanged(
                device, STATE_DISCONNECTED, STATE_CONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate Headset disconnected for a device. */
    private void headsetDisconnected(BluetoothDevice device) {
        mSilenceDeviceManager.hfpConnectionStateChanged(
                device, STATE_CONNECTED, STATE_DISCONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }
}
