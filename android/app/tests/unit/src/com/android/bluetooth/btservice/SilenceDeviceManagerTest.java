/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.tests.bluetooth.MockitoRule;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

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

import java.util.Optional;

/** Test cases for {@link SilenceDeviceManager}. */
@SmallTest
@RunWith(TestParameterInjector.class)
public class SilenceDeviceManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;

    private final BluetoothDevice mDevice = getTestDevice(28);

    private SilenceDeviceManager mManager;
    private HandlerThread mHandlerThread;
    private Looper mLooper;
    private InOrder mInOrder;

    @Before
    public void setUp() {
        mInOrder = inOrder(mAdapterService);
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();

        mHandlerThread = new HandlerThread("SilenceManagerTestHandlerThread");
        mHandlerThread.start();
        mLooper = mHandlerThread.getLooper();
        mManager = new SilenceDeviceManager(mAdapterService, mLooper);
    }

    @After
    public void tearDown() {
        mManager.cleanup();
        mHandlerThread.quit();
    }

    @Test
    public void setGetDeviceSilence(
            @TestParameter boolean wasSilenced, @TestParameter boolean enableSilence) {
        doReturn(true).when(mA2dpService).setSilenceMode(mDevice, enableSilence);
        doReturn(true).when(mHeadsetService).setSilenceMode(mDevice, enableSilence);

        // Send A2DP/HFP connected intent
        a2dpConnected(mDevice);
        headsetConnected(mDevice);

        // Set pre-state for mSilenceDeviceManager
        if (wasSilenced) {
            assertThat(mManager.setSilenceMode(mDevice, true)).isTrue();
            TestUtils.waitForLooperToFinishScheduledTask(mLooper);
            verifySilenceStateIntent();
        }

        // Set silence state and check whether state changed successfully
        assertThat(mManager.setSilenceMode(mDevice, enableSilence)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
        assertThat(mManager.getSilenceMode(mDevice)).isEqualTo(enableSilence);

        // Check for silence state changed intent
        if (wasSilenced != enableSilence) {
            verifySilenceStateIntent();
        }

        // Remove test devices
        a2dpDisconnected(mDevice);
        headsetDisconnected(mDevice);

        assertThat(mManager.getSilenceMode(mDevice)).isFalse();
        if (enableSilence) {
            // If the silence mode is enabled, it should be automatically disabled
            // after device is disconnected.
            verifyIntentSent();
        }
    }

    @Test
    public void testSetGetDeviceSilenceDisconnectedCase(@TestParameter boolean enableSilence) {
        // Set silence mode and it should stay disabled
        assertThat(mManager.setSilenceMode(mDevice, enableSilence)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
        assertThat(mManager.getSilenceMode(mDevice)).isFalse();

        verifyNoIntentSent(); // Should be no intent been broadcasted
    }

    /** Helper to indicate A2dp connected for a device. */
    private void a2dpConnected(BluetoothDevice device) {
        mManager.a2dpConnectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate A2dp disconnected for a device. */
    private void a2dpDisconnected(BluetoothDevice device) {
        mManager.a2dpConnectionStateChanged(device, STATE_CONNECTED, STATE_DISCONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate Headset connected for a device. */
    private void headsetConnected(BluetoothDevice device) {
        mManager.hfpConnectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    /** Helper to indicate Headset disconnected for a device. */
    private void headsetDisconnected(BluetoothDevice device) {
        mManager.hfpConnectionStateChanged(device, STATE_CONNECTED, STATE_DISCONNECTED);
        TestUtils.waitForLooperToFinishScheduledTask(mLooper);
    }

    private void verifyNoIntentSent() {
        if (Flags.onlyBroadcastToLocalUser()) {
            mInOrder.verify(mAdapterService, never()).sendBroadcast(any(), any(), any());
        } else {
            mInOrder.verify(mAdapterService, never())
                    .sendBroadcastAsUser(any(), any(), any(), any());
        }
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        if (Flags.onlyBroadcastToLocalUser()) {
            mInOrder.verify(mAdapterService)
                    .sendBroadcast(
                            MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                            eq(BLUETOOTH_CONNECT),
                            any(Bundle.class));
        } else {
            mInOrder.verify(mAdapterService)
                    .sendBroadcastAsUser(
                            MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                            eq(UserHandle.ALL),
                            eq(BLUETOOTH_CONNECT),
                            any(Bundle.class));
        }
    }

    private void verifySilenceStateIntent() {
        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_SILENCE_MODE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice));
    }
}
