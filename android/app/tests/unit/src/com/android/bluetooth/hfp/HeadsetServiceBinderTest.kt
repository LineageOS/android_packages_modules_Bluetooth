/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link HeadsetServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HeadsetServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private HeadsetService mService;

    private final BluetoothDevice mDevice = getTestDevice(39);

    private HeadsetServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new HeadsetServiceBinder(mService);
    }

    @Test
    public void connect() {
        mBinder.connect(mDevice, mSource);
        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mDevice, mSource);
        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mSource);
        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void isNoiseReductionSupported() {
        mBinder.isNoiseReductionSupported(mDevice, mSource);
        verify(mService).isNoiseReductionSupported(mDevice);
    }

    @Test
    public void isVoiceRecognitionSupported() {
        mBinder.isVoiceRecognitionSupported(mDevice, mSource);
        verify(mService).isVoiceRecognitionSupported(mDevice);
    }

    @Test
    public void startVoiceRecognition() {
        mBinder.startVoiceRecognition(mDevice, mSource);
        verify(mService).startVoiceRecognition(mDevice);
    }

    @Test
    public void stopVoiceRecognition() {
        mBinder.stopVoiceRecognition(mDevice, mSource);
        verify(mService).stopVoiceRecognition(mDevice);
    }

    @Test
    public void isAudioConnected() {
        mBinder.isAudioConnected(mDevice, mSource);
        verify(mService).isAudioConnected(mDevice);
    }

    @Test
    public void getAudioState() {
        mBinder.getAudioState(mDevice, mSource);
        verify(mService).getAudioState(mDevice);
    }

    @Test
    public void connectAudio() {
        mBinder.connectAudio(mSource);
        verify(mService).connectAudio();
    }

    @Test
    public void disconnectAudio() {
        mBinder.disconnectAudio(mSource);
        verify(mService).disconnectAudio();
    }

    @Test
    public void setAudioRouteAllowed() {
        boolean allowed = true;

        mBinder.setAudioRouteAllowed(allowed, mSource);
        verify(mService).setAudioRouteAllowed(allowed);
    }

    @Test
    public void getAudioRouteAllowed() {
        mBinder.getAudioRouteAllowed(mSource);
        verify(mService).getAudioRouteAllowed();
    }

    @Test
    public void startScoUsingVirtualVoiceCall() {
        mBinder.startScoUsingVirtualVoiceCall(mSource);
        verify(mService).startScoUsingVirtualVoiceCall();
    }

    @Test
    public void stopScoUsingVirtualVoiceCall() {
        mBinder.stopScoUsingVirtualVoiceCall(mSource);
        verify(mService).stopScoUsingVirtualVoiceCall();
    }
}
