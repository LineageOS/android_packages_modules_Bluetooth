/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class VolumeControlNativeCallbackTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private VolumeControlService mService;
    @Captor private ArgumentCaptor<VolumeControlStackEvent> mEvent;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private VolumeControlNativeCallback mNativeCallback;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public VolumeControlNativeCallbackTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).isAvailable();
        mNativeCallback = new VolumeControlNativeCallback(mAdapterService, mService);
    }

    @Test
    public void onConnectionStateChanged() {
        int state = STATE_CONNECTED;

        mNativeCallback.onConnectionStateChanged(state, getByteAddress(mDevice));
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        expect.that(event.valueInt1).isEqualTo(state);
    }

    @Test
    public void onVolumeStateChanged() {
        int volume = 3;
        boolean mute = false;
        int flags = 1;
        boolean isAutonomous = false;

        mNativeCallback.onVolumeStateChanged(
                volume, mute, flags, getByteAddress(mDevice), isAutonomous);
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_VOLUME_STATE_CHANGED);
    }

    @Test
    public void onGroupVolumeStateChanged() {
        int volume = 3;
        boolean mute = false;
        int groupId = 1;
        boolean isAutonomous = false;

        mNativeCallback.onGroupVolumeStateChanged(volume, mute, groupId, isAutonomous);
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_VOLUME_STATE_CHANGED);
        expect.that(event.valueInt1).isEqualTo(groupId);
        expect.that(event.valueInt2).isEqualTo(volume);
        expect.that(event.valueBool1).isEqualTo(mute);
        expect.that(event.valueBool2).isEqualTo(isAutonomous);
    }

    @Test
    public void onDeviceAvailable() {
        int groupId = 1;
        int numOfExternalOutputs = 3;
        int numOfExternalInputs = 0;

        mNativeCallback.onDeviceAvailable(
                groupId, numOfExternalOutputs, numOfExternalInputs, getByteAddress(mDevice));
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_DEVICE_AVAILABLE);
    }

    @Test
    public void onExtAudioOutVolumeOffsetChanged() {
        int externalOutputId = 2;
        int offset = 0;

        mNativeCallback.onExtAudioOutVolumeOffsetChanged(
                externalOutputId, offset, getByteAddress(mDevice));
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED);
    }

    @Test
    public void onExtAudioOutLocationChanged() {
        int externalOutputId = 2;
        int location = 100;

        mNativeCallback.onExtAudioOutLocationChanged(
                externalOutputId, location, getByteAddress(mDevice));
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED);
    }

    @Test
    public void onExtAudioOutDescriptionChanged() {
        int externalOutputId = 2;
        String descr = "test-descr";

        mNativeCallback.onExtAudioOutDescriptionChanged(
                externalOutputId, descr, getByteAddress(mDevice));
        verify(mService).messageFromNative(mEvent.capture());
        VolumeControlStackEvent event = mEvent.getValue();

        expect.that(event.type).isEqualTo(EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED);
    }

    @Test
    public void onExtAudioInStateChanged() {
        int id = 2;
        int gainSetting = 1;
        int gainMode = 0;
        int mute = 0;

        mNativeCallback.onExtAudioInStateChanged(
                id, gainSetting, mute, gainMode, getByteAddress(mDevice));
        verify(mService)
                .onExtAudioInStateChanged(any(), eq(id), eq(gainSetting), eq(mute), eq(gainMode));
    }

    @Test
    public void onExtAudioInStatusChanged() {
        int id = 2;
        int status = 1;

        mNativeCallback.onExtAudioInStatusChanged(id, status, getByteAddress(mDevice));
        verify(mService).onExtAudioInStatusChanged(any(), eq(id), eq(status));
    }

    @Test
    public void onExtAudioInTypeChanged() {
        int id = 2;
        int type = 1;

        mNativeCallback.onExtAudioInTypeChanged(id, type, getByteAddress(mDevice));
        verify(mService).onExtAudioInTypeChanged(any(), eq(id), eq(type));
    }

    @Test
    public void onExtAudioInDescriptionChanged() {
        int id = 2;
        String description = "microphone";
        boolean isWritable = true;

        mNativeCallback.onExtAudioInDescriptionChanged(
                id, description, isWritable, getByteAddress(mDevice));
        verify(mService)
                .onExtAudioInDescriptionChanged(any(), eq(id), eq(description), eq(isWritable));
    }

    @Test
    public void onExtAudioInGainSettingPropertiesChanged() {
        int id = 2;
        int unit = 1;
        int min = 0;
        int max = 100;

        mNativeCallback.onExtAudioInGainSettingPropertiesChanged(
                id, unit, min, max, getByteAddress(mDevice));
        verify(mService)
                .onExtAudioInGainSettingPropertiesChanged(
                        any(), eq(id), eq(unit), eq(min), eq(max));
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        return Util.getBytesFromAddress(device.getAddress());
    }
}
