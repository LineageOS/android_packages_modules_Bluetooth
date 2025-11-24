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

package com.android.bluetooth.hfpclient;

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

/** Test cases for {@link HeadsetClientServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HeadsetClientServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private HeadsetClientService mService;

    private final BluetoothDevice mDevice = getTestDevice(54);

    private HeadsetClientServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new HeadsetClientServiceBinder(mService);
    }

    @Test
    public void connect_callsServiceMethod() {
        mBinder.connect(mDevice, mSource);

        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect_callsServiceMethod() {
        mBinder.disconnect(mDevice, mSource);

        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices_callsServiceMethod() {
        mBinder.getConnectedDevices(mSource);

        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates_callsServiceMethod() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mSource);

        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState_callsServiceMethod() {
        mBinder.getConnectionState(mDevice, mSource);

        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy_callsServiceMethod() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mSource);

        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy_callsServiceMethod() {
        mBinder.getConnectionPolicy(mDevice, mSource);

        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void startVoiceRecognition_callsServiceMethod() {
        mBinder.startVoiceRecognition(mDevice, mSource);

        verify(mService).startVoiceRecognition(mDevice);
    }

    @Test
    public void stopVoiceRecognition_callsServiceMethod() {
        mBinder.stopVoiceRecognition(mDevice, mSource);

        verify(mService).stopVoiceRecognition(mDevice);
    }

    @Test
    public void getAudioState_callsServiceMethod() {
        mBinder.getAudioState(mDevice, mSource);

        verify(mService).getAudioState(mDevice);
    }

    @Test
    public void setAudioRouteAllowed_callsServiceMethod() {
        boolean allowed = true;
        mBinder.setAudioRouteAllowed(mDevice, allowed, mSource);

        verify(mService).setAudioRouteAllowed(mDevice, allowed);
    }

    @Test
    public void getAudioRouteAllowed_callsServiceMethod() {
        mBinder.getAudioRouteAllowed(mDevice, mSource);

        verify(mService).getAudioRouteAllowed(mDevice);
    }

    @Test
    public void connectAudio_callsServiceMethod() {
        mBinder.connectAudio(mDevice, mSource);

        verify(mService).connectAudio(mDevice);
    }

    @Test
    public void disconnectAudio_callsServiceMethod() {
        mBinder.disconnectAudio(mDevice, mSource);

        verify(mService).disconnectAudio(mDevice);
    }

    @Test
    public void acceptCall_callsServiceMethod() {
        int flag = 2;
        mBinder.acceptCall(mDevice, flag, mSource);

        verify(mService).acceptCall(mDevice, flag);
    }

    @Test
    public void rejectCall_callsServiceMethod() {
        mBinder.rejectCall(mDevice, mSource);

        verify(mService).rejectCall(mDevice);
    }

    @Test
    public void holdCall_callsServiceMethod() {
        mBinder.holdCall(mDevice, mSource);

        verify(mService).holdCall(mDevice);
    }

    @Test
    public void terminateCall_callsServiceMethod() {
        mBinder.terminateCall(mDevice, null, mSource);

        verify(mService).terminateCall(mDevice, null);
    }

    @Test
    public void explicitCallTransfer_callsServiceMethod() {
        mBinder.explicitCallTransfer(mDevice, mSource);

        verify(mService).explicitCallTransfer(mDevice);
    }

    @Test
    public void enterPrivateMode_callsServiceMethod() {
        int index = 1;
        mBinder.enterPrivateMode(mDevice, index, mSource);

        verify(mService).enterPrivateMode(mDevice, index);
    }

    @Test
    public void dial_callsServiceMethod() {
        String number = "12532523";
        mBinder.dial(mDevice, number, mSource);

        verify(mService).dial(mDevice, number);
    }

    @Test
    public void sendDTMF_callsServiceMethod() {
        byte code = 21;
        mBinder.sendDTMF(mDevice, code, mSource);

        verify(mService).sendDTMF(mDevice, code);
    }

    @Test
    public void getCurrentAgEvents_callsServiceMethod() {
        mBinder.getCurrentAgEvents(mDevice, mSource);

        verify(mService).getCurrentAgEvents(mDevice);
    }

    @Test
    public void sendVendorAtCommand_callsServiceMethod() {
        int vendorId = 5;
        String cmd = "test_command";

        mBinder.sendVendorAtCommand(mDevice, vendorId, cmd, mSource);

        verify(mService).sendVendorAtCommand(mDevice, vendorId, cmd);
    }

    @Test
    public void getCurrentAgFeatures_callsServiceMethod() {
        mBinder.getCurrentAgFeatures(mDevice, mSource);

        verify(mService).getCurrentAgFeaturesBundle(mDevice);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
