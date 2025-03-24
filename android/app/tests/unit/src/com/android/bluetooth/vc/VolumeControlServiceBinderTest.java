/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.vc;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IAudioInputCallback;
import android.content.AttributionSource;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link VolumeControlServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private VolumeControlService mService;

    private final BluetoothDevice mDevice = getTestDevice(25);

    private AttributionSource mAttributionSource;
    private VolumeControlServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new VolumeControlServiceBinder(mService);
        mAttributionSource = new AttributionSource.Builder(1).build();
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
        int connectionPolicy = 1;

        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void isVolumeOffsetAvailable() {
        mBinder.isVolumeOffsetAvailable(mDevice, mAttributionSource);
        verify(mService).isVolumeOffsetAvailable(mDevice);
    }

    @Test
    public void getNumberOfVolumeOffsetInstances() {
        mBinder.getNumberOfVolumeOffsetInstances(mDevice, mAttributionSource);
        verify(mService).getNumberOfVolumeOffsetInstances(mDevice);
    }

    @Test
    public void setVolumeOffset() {
        int instanceId = 1;
        int volumeOffset = 2;

        mBinder.setVolumeOffset(mDevice, instanceId, volumeOffset, mAttributionSource);
        verify(mService).setVolumeOffset(mDevice, instanceId, volumeOffset);
    }

    @Test
    public void setDeviceVolume() {
        int volume = 1;
        boolean isGroupOp = true;

        mBinder.setDeviceVolume(mDevice, volume, isGroupOp, mAttributionSource);
        verify(mService).setDeviceVolume(mDevice, volume, isGroupOp);
    }

    @Test
    public void setGroupVolume() {
        int groupId = 1;
        int volume = 2;

        mBinder.setGroupVolume(groupId, volume, mAttributionSource);
        verify(mService).setGroupVolume(groupId, volume);
    }

    @Test
    public void getGroupVolume() {
        int groupId = 1;

        mBinder.getGroupVolume(groupId, mAttributionSource);
        verify(mService).getGroupVolume(groupId);
    }

    @Test
    public void setGroupActive() {
        int groupId = 1;
        boolean active = true;

        mBinder.setGroupActive(groupId, active, mAttributionSource);
        verify(mService).setGroupActive(groupId, active);
    }

    @Test
    public void mute() {
        mBinder.mute(mDevice, mAttributionSource);
        verify(mService).mute(mDevice);
    }

    @Test
    public void muteGroup() {
        int groupId = 1;
        mBinder.muteGroup(groupId, mAttributionSource);
        verify(mService).muteGroup(groupId);
    }

    @Test
    public void unmute() {
        mBinder.unmute(mDevice, mAttributionSource);
        verify(mService).unmute(mDevice);
    }

    @Test
    public void unmuteGroup() {
        int groupId = 1;

        mBinder.unmuteGroup(groupId, mAttributionSource);
        verify(mService).unmuteGroup(groupId);
    }

    @Test
    public void getNumberOfAudioInputControlServices() {
        mBinder.getNumberOfAudioInputControlServices(mAttributionSource, mDevice);
    }

    @Test
    public void registerAudioInputControlCallback() {
        int instanceId = 1;
        IAudioInputCallback callback = mock(IAudioInputCallback.class);

        mBinder.registerAudioInputControlCallback(
                mAttributionSource, mDevice, instanceId, callback);
    }

    @Test
    public void unregisterAudioInputControlCallback() {
        int instanceId = 1;
        IAudioInputCallback callback = mock(IAudioInputCallback.class);

        mBinder.unregisterAudioInputControlCallback(
                mAttributionSource, mDevice, instanceId, callback);
    }

    @Test
    public void getAudioInputGainSettingUnit() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingUnit(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSettingMin() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingMin(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSettingMax() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingMax(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputDescription() {
        int instanceId = 1;
        mBinder.getAudioInputDescription(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void isAudioInputDescriptionWritable() {
        int instanceId = 1;
        mBinder.isAudioInputDescriptionWritable(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputDescription() {
        int instanceId = 1;
        String description = "test";
        mBinder.setAudioInputDescription(mAttributionSource, mDevice, instanceId, description);
    }

    @Test
    public void getAudioInputStatus() {
        int instanceId = 1;
        mBinder.getAudioInputStatus(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputType() {
        int instanceId = 1;
        mBinder.getAudioInputType(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSetting() {
        int instanceId = 1;
        mBinder.getAudioInputGainSetting(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputGainSetting() {
        int instanceId = 1;
        int gainSetting = 2;
        mBinder.setAudioInputGainSetting(mAttributionSource, mDevice, instanceId, gainSetting);
    }

    @Test
    public void getAudioInputGainMode() {
        int instanceId = 1;
        mBinder.getAudioInputGainMode(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputGainMode() {
        int instanceId = 1;
        int gainMode = 2;
        mBinder.setAudioInputGainMode(mAttributionSource, mDevice, instanceId, gainMode);
    }

    @Test
    public void getAudioInputMute() {
        int instanceId = 1;
        mBinder.getAudioInputMute(mAttributionSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputMute() {
        int instanceId = 1;
        int mute = 2;
        mBinder.setAudioInputMute(mAttributionSource, mDevice, instanceId, mute);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
