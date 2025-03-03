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

package com.android.bluetooth.hfp;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

public class BluetoothHeadsetBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HeadsetService mService;

    private final AttributionSource mAttributionSource = new AttributionSource.Builder(1).build();
    private BluetoothDevice mDevice = getTestDevice(39);

    private HeadsetService.BluetoothHeadsetBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new HeadsetService.BluetoothHeadsetBinder(mService);
    }

    @Test
    public void connect() {
        mBinder.connect(mDevice, mAttributionSource);
        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mDevice, mAttributionSource);
        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void isNoiseReductionSupported() {
        mBinder.isNoiseReductionSupported(mDevice, mAttributionSource);
        verify(mService).isNoiseReductionSupported(mDevice);
    }

    @Test
    public void isVoiceRecognitionSupported() {
        mBinder.isVoiceRecognitionSupported(mDevice, mAttributionSource);
        verify(mService).isVoiceRecognitionSupported(mDevice);
    }

    @Test
    public void startVoiceRecognition() {
        mBinder.startVoiceRecognition(mDevice, mAttributionSource);
        verify(mService).startVoiceRecognition(mDevice);
    }

    @Test
    public void stopVoiceRecognition() {
        mBinder.stopVoiceRecognition(mDevice, mAttributionSource);
        verify(mService).stopVoiceRecognition(mDevice);
    }

    @Test
    public void isAudioConnected() {
        mBinder.isAudioConnected(mDevice, mAttributionSource);
        verify(mService).isAudioConnected(mDevice);
    }

    @Test
    public void getAudioState() {
        mBinder.getAudioState(mDevice, mAttributionSource);
        verify(mService).getAudioState(mDevice);
    }

    @Test
    public void connectAudio() {
        mBinder.connectAudio(mAttributionSource);
        verify(mService).connectAudio();
    }

    @Test
    public void disconnectAudio() {
        mBinder.disconnectAudio(mAttributionSource);
        verify(mService).disconnectAudio();
    }

    @Test
    public void setAudioRouteAllowed() {
        boolean allowed = true;
        mBinder.setAudioRouteAllowed(allowed, mAttributionSource);
        verify(mService).setAudioRouteAllowed(allowed);
    }

    @Test
    public void getAudioRouteAllowed() {
        mBinder.getAudioRouteAllowed(mAttributionSource);
        verify(mService).getAudioRouteAllowed();
    }

    @Test
    public void setForceScoAudio() {
        boolean forced = true;
        mBinder.setForceScoAudio(forced, mAttributionSource);
        verify(mService).setForceScoAudio(forced);
    }

    @Test
    public void startScoUsingVirtualVoiceCall() {
        mBinder.startScoUsingVirtualVoiceCall(mAttributionSource);
        verify(mService).startScoUsingVirtualVoiceCall();
    }

    @Test
    public void stopScoUsingVirtualVoiceCall() {
        mBinder.stopScoUsingVirtualVoiceCall(mAttributionSource);
        verify(mService).stopScoUsingVirtualVoiceCall();
    }
}
