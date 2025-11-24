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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IAudioInputCallback;
import android.content.AttributionSource;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.function.Consumer;

/** Test cases for {@link VolumeControlServiceBinder}. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class VolumeControlServiceBinderTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private VolumeControlService mService;

    private final BluetoothDevice mDevice = getTestDevice(25);

    private VolumeControlServiceBinder mBinder;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public VolumeControlServiceBinderTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).isAvailable();
        doAnswer(
                        inv -> {
                            ((Consumer) inv.getArgument(0)).accept(mService);
                            return null;
                        })
                .when(mService)
                .post(any());
        mBinder = new VolumeControlServiceBinder(mService);
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
    public void isVolumeOffsetAvailable() {
        mBinder.isVolumeOffsetAvailable(mDevice, mSource);
        verify(mService).isVolumeOffsetAvailable(mDevice);
    }

    @Test
    public void getNumberOfVolumeOffsetInstances() {
        mBinder.getNumberOfVolumeOffsetInstances(mDevice, mSource);
        verify(mService).getNumberOfVolumeOffsetInstances(mDevice);
    }

    @Test
    public void setVolumeOffset() {
        int instanceId = 1;
        int volumeOffset = 2;

        mBinder.setVolumeOffset(mDevice, instanceId, volumeOffset, mSource);
        verify(mService).setVolumeOffset(mDevice, instanceId, volumeOffset);
    }

    @Test
    public void setDeviceVolume() {
        int volume = 1;
        boolean isGroupOp = true;

        mBinder.setDeviceVolume(mDevice, volume, isGroupOp, mSource);
        verify(mService).setDeviceVolume(mDevice, volume, isGroupOp);
    }

    @Test
    public void getNumberOfAudioInputControlServices() {
        mBinder.getNumberOfAudioInputControlServices(mSource, mDevice);
    }

    @Test
    public void registerAudioInputControlCallback() {
        int instanceId = 1;
        IAudioInputCallback callback = mock(IAudioInputCallback.class);

        mBinder.registerAudioInputControlCallback(mSource, mDevice, instanceId, callback);
    }

    @Test
    public void unregisterAudioInputControlCallback() {
        int instanceId = 1;
        IAudioInputCallback callback = mock(IAudioInputCallback.class);

        mBinder.unregisterAudioInputControlCallback(mSource, mDevice, instanceId, callback);
    }

    @Test
    public void getAudioInputGainSettingUnit() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingUnit(mSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSettingMin() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingMin(mSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSettingMax() {
        int instanceId = 1;
        mBinder.getAudioInputGainSettingMax(mSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputDescription() {
        int instanceId = 1;
        mBinder.getAudioInputDescription(mSource, mDevice, instanceId);
    }

    @Test
    public void isAudioInputDescriptionWritable() {
        int instanceId = 1;
        mBinder.isAudioInputDescriptionWritable(mSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputDescription() {
        int instanceId = 1;
        String description = "test";
        mBinder.setAudioInputDescription(mSource, mDevice, instanceId, description);
    }

    @Test
    public void getAudioInputStatus() {
        int instanceId = 1;
        mBinder.getAudioInputStatus(mSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputType() {
        int instanceId = 1;
        mBinder.getAudioInputType(mSource, mDevice, instanceId);
    }

    @Test
    public void getAudioInputGainSetting() {
        int instanceId = 1;
        mBinder.getAudioInputGainSetting(mSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputGainSetting() {
        int instanceId = 1;
        int gainSetting = 2;
        mBinder.setAudioInputGainSetting(mSource, mDevice, instanceId, gainSetting);
    }

    @Test
    public void getAudioInputGainMode() {
        int instanceId = 1;
        mBinder.getAudioInputGainMode(mSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputGainMode() {
        int instanceId = 1;
        int gainMode = 2;
        mBinder.setAudioInputGainMode(mSource, mDevice, instanceId, gainMode);
    }

    @Test
    public void getAudioInputMute() {
        int instanceId = 1;
        mBinder.getAudioInputMute(mSource, mDevice, instanceId);
    }

    @Test
    public void setAudioInputMute() {
        int instanceId = 1;
        int mute = 2;
        mBinder.setAudioInputMute(mSource, mDevice, instanceId, mute);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
