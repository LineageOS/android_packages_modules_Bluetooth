/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.avrcp.AvrcpVolumeManager.AVRCP_MAX_VOL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioManager;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpVolumeManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public TestName testName = new TestName();

    @Mock private AvrcpNativeInterface mNativeInterface;
    @Mock private AdapterService mAdapterService;
    @Mock AudioManager mAudioManager;

    private static final int TEST_DEVICE_MAX_VOLUME = 25;

    private final BluetoothDevice mDevice = getTestDevice(40);

    AvrcpVolumeManager mAvrcpVolumeManager;

    @Before
    public void setUp() {
        doReturn(TEST_DEVICE_MAX_VOLUME)
                .when(mAudioManager)
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
        doReturn(
                        ctx.getSharedPreferences(
                                testName.getMethodName() + "TmpPref", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        mAvrcpVolumeManager =
                new AvrcpVolumeManager(mAdapterService, mAudioManager, mNativeInterface);
    }

    @Test
    public void avrcpVolumeConversion() {
        assertThat(mAvrcpVolumeManager.avrcpToSystemVolume(0)).isEqualTo(0);
        assertThat(mAvrcpVolumeManager.avrcpToSystemVolume(AVRCP_MAX_VOL))
                .isEqualTo(TEST_DEVICE_MAX_VOLUME);

        assertThat(mAvrcpVolumeManager.systemToAvrcpVolume(0)).isEqualTo(0);
        assertThat(mAvrcpVolumeManager.systemToAvrcpVolume(TEST_DEVICE_MAX_VOLUME))
                .isEqualTo(AVRCP_MAX_VOL);
    }

    @Test
    public void dump() {
        StringBuilder sb = new StringBuilder();
        mAvrcpVolumeManager.dump(sb);

        assertThat(sb.toString()).isNotEmpty();
    }

    @Test
    public void sendVolumeChanged() {
        mAvrcpVolumeManager.sendVolumeChanged(mDevice, TEST_DEVICE_MAX_VOLUME);

        verify(mNativeInterface).sendVolumeChanged(mDevice, AVRCP_MAX_VOL);
    }

    @Test
    public void setVolume() {
        mAvrcpVolumeManager.setVolume(mDevice, AVRCP_MAX_VOL);

        verify(mAudioManager)
                .setStreamVolume(
                        eq(AudioManager.STREAM_MUSIC), eq(TEST_DEVICE_MAX_VOLUME), anyInt());
    }

    @Test
    public void switchVolumeDevice() throws InterruptedException {
        mAvrcpVolumeManager.volumeDeviceSwitched(mDevice);
        mAvrcpVolumeManager.deviceConnected(mDevice, true);

        // verify whether switchVolumeDevice is called by checking
        // mAudioManager.setDeviceVolumeBehavior().
        // Since it's done in an async thread, we need to add a timeout to await completion
        verify(mAudioManager, timeout(1_000)).setDeviceVolumeBehavior(any(), anyInt());
    }

    @Test
    public void switchVolumeDevice_reverseEventOrder() throws InterruptedException {
        mAvrcpVolumeManager.deviceConnected(mDevice, true);
        mAvrcpVolumeManager.volumeDeviceSwitched(mDevice);

        // verify whether switchVolumeDevice is called by checking
        // mAudioManager.setDeviceVolumeBehavior().
        // Since it's done in an async thread, we need to add a timeout to await completion
        verify(mAudioManager, timeout(1_000)).setDeviceVolumeBehavior(any(), anyInt());
    }
}
