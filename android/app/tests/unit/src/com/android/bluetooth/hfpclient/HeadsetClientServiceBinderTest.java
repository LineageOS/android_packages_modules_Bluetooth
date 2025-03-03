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

package com.android.bluetooth.hfpclient;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HeadsetClientServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HeadsetClientService mService;

    private final BluetoothDevice mDevice = getTestDevice(54);

    HeadsetClientService.BluetoothHeadsetClientBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new HeadsetClientService.BluetoothHeadsetClientBinder(mService);
    }

    @Test
    public void connect_callsServiceMethod() {
        mBinder.connect(mDevice, null);

        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect_callsServiceMethod() {
        mBinder.disconnect(mDevice, null);

        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices_callsServiceMethod() {
        mBinder.getConnectedDevices(null);

        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates_callsServiceMethod() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, null);

        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState_callsServiceMethod() {
        mBinder.getConnectionState(mDevice, null);

        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy_callsServiceMethod() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mDevice, connectionPolicy, null);

        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy_callsServiceMethod() {
        mBinder.getConnectionPolicy(mDevice, null);

        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void startVoiceRecognition_callsServiceMethod() {
        mBinder.startVoiceRecognition(mDevice, null);

        verify(mService).startVoiceRecognition(mDevice);
    }

    @Test
    public void stopVoiceRecognition_callsServiceMethod() {
        mBinder.stopVoiceRecognition(mDevice, null);

        verify(mService).stopVoiceRecognition(mDevice);
    }

    @Test
    public void getAudioState_callsServiceMethod() {
        mBinder.getAudioState(mDevice, null);

        verify(mService).getAudioState(mDevice);
    }

    @Test
    public void setAudioRouteAllowed_callsServiceMethod() {
        boolean allowed = true;
        mBinder.setAudioRouteAllowed(mDevice, allowed, null);

        verify(mService).setAudioRouteAllowed(mDevice, allowed);
    }

    @Test
    public void getAudioRouteAllowed_callsServiceMethod() {
        mBinder.getAudioRouteAllowed(mDevice, null);

        verify(mService).getAudioRouteAllowed(mDevice);
    }

    @Test
    public void connectAudio_callsServiceMethod() {
        mBinder.connectAudio(mDevice, null);

        verify(mService).connectAudio(mDevice);
    }

    @Test
    public void disconnectAudio_callsServiceMethod() {
        mBinder.disconnectAudio(mDevice, null);

        verify(mService).disconnectAudio(mDevice);
    }

    @Test
    public void acceptCall_callsServiceMethod() {
        int flag = 2;
        mBinder.acceptCall(mDevice, flag, null);

        verify(mService).acceptCall(mDevice, flag);
    }

    @Test
    public void rejectCall_callsServiceMethod() {
        mBinder.rejectCall(mDevice, null);

        verify(mService).rejectCall(mDevice);
    }

    @Test
    public void holdCall_callsServiceMethod() {
        mBinder.holdCall(mDevice, null);

        verify(mService).holdCall(mDevice);
    }

    @Test
    public void terminateCall_callsServiceMethod() {
        mBinder.terminateCall(mDevice, null, null);

        verify(mService).terminateCall(mDevice, null);
    }

    @Test
    public void explicitCallTransfer_callsServiceMethod() {
        mBinder.explicitCallTransfer(mDevice, null);

        verify(mService).explicitCallTransfer(mDevice);
    }

    @Test
    public void enterPrivateMode_callsServiceMethod() {
        int index = 1;
        mBinder.enterPrivateMode(mDevice, index, null);

        verify(mService).enterPrivateMode(mDevice, index);
    }

    @Test
    public void dial_callsServiceMethod() {
        String number = "12532523";
        mBinder.dial(mDevice, number, null);

        verify(mService).dial(mDevice, number);
    }

    @Test
    public void sendDTMF_callsServiceMethod() {
        byte code = 21;
        mBinder.sendDTMF(mDevice, code, null);

        verify(mService).sendDTMF(mDevice, code);
    }

    @Test
    public void getLastVoiceTagNumber_callsServiceMethod() {
        mBinder.getLastVoiceTagNumber(mDevice, null);

        verify(mService).getLastVoiceTagNumber(mDevice);
    }

    @Test
    public void getCurrentAgEvents_callsServiceMethod() {
        mBinder.getCurrentAgEvents(mDevice, null);

        verify(mService).getCurrentAgEvents(mDevice);
    }

    @Test
    public void sendVendorAtCommand_callsServiceMethod() {
        int vendorId = 5;
        String cmd = "test_command";

        mBinder.sendVendorAtCommand(mDevice, vendorId, cmd, null);

        verify(mService).sendVendorAtCommand(mDevice, vendorId, cmd);
    }

    @Test
    public void getCurrentAgFeatures_callsServiceMethod() {
        mBinder.getCurrentAgFeatures(mDevice, null);

        verify(mService).getCurrentAgFeaturesBundle(mDevice);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
