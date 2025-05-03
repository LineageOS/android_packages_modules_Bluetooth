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

package com.android.bluetooth.a2dp;

import static android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link A2dpServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class A2dpServiceBinderTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mAttributionSource;
    @Mock private A2dpService mA2dpService;
    @Mock private PackageManager mPackageManager;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private A2dpServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        doReturn(mPackageManager).when(mA2dpService).getPackageManager();
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
        doReturn(appInfo).when(mPackageManager).getApplicationInfo(any(), anyInt());

        mBinder = new A2dpServiceBinder(mA2dpService);
    }

    @After
    public void cleanUp() {
        mBinder.cleanup();
    }

    @Test
    public void connect() {
        mBinder.connect(mDevice, mAttributionSource);
        verify(mA2dpService).connect(mDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mDevice, mAttributionSource);
        verify(mA2dpService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mA2dpService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mA2dpService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mA2dpService).getConnectionState(mDevice);
    }

    @Test
    public void setActiveDevice() {
        mBinder.setActiveDevice(mDevice, mAttributionSource);
        verify(mA2dpService).setActiveDevice(mDevice);
    }

    @Test
    public void setActiveDevice_withNull_callsRemoveActiveDevice() {
        mBinder.setActiveDevice(null, mAttributionSource);
        verify(mA2dpService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevice() {
        mBinder.getActiveDevice(mAttributionSource);
        verify(mA2dpService).getActiveDevice();
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;

        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mAttributionSource);
        verify(mA2dpService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mA2dpService).getConnectionPolicy(mDevice);
    }

    @Test
    public void setAvrcpAbsoluteVolume() {
        int volume = 3;

        mBinder.setAvrcpAbsoluteVolume(volume, mAttributionSource);
        verify(mA2dpService).setAvrcpAbsoluteVolume(volume);
    }

    @Test
    public void isA2dpPlaying() {
        mBinder.isA2dpPlaying(mDevice, mAttributionSource);
        verify(mA2dpService).isA2dpPlaying(mDevice);
    }

    @Test
    public void getCodecStatus() {
        mBinder.getCodecStatus(mDevice, mAttributionSource);
        verify(mA2dpService).getCodecStatus(mDevice);
    }

    @Test
    public void setCodecConfigPreference() {
        BluetoothCodecConfig config = new BluetoothCodecConfig(SOURCE_CODEC_TYPE_INVALID);

        mBinder.setCodecConfigPreference(mDevice, config, mAttributionSource);
        verify(mA2dpService).setCodecConfigPreference(mDevice, config);
    }

    @Test
    public void enableOptionalCodecs() {

        mBinder.enableOptionalCodecs(mDevice, mAttributionSource);
        verify(mA2dpService).enableOptionalCodecs(mDevice);
    }

    @Test
    public void disableOptionalCodecs() {

        mBinder.disableOptionalCodecs(mDevice, mAttributionSource);
        verify(mA2dpService).disableOptionalCodecs(mDevice);
    }

    @Test
    public void isOptionalCodecsSupported() {
        mBinder.isOptionalCodecsSupported(mDevice, mAttributionSource);
        verify(mA2dpService).getSupportsOptionalCodecs(mDevice);
    }

    @Test
    public void isOptionalCodecsEnabled() {
        mBinder.isOptionalCodecsEnabled(mDevice, mAttributionSource);
        verify(mA2dpService).getOptionalCodecsEnabled(mDevice);
    }

    @Test
    public void setOptionalCodecsEnabled() {
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;

        mBinder.setOptionalCodecsEnabled(mDevice, value, mAttributionSource);
        verify(mA2dpService).setOptionalCodecsEnabled(mDevice, value);
    }

    @Test
    public void getDynamicBufferSupport() {
        mBinder.getDynamicBufferSupport(mAttributionSource);
        verify(mA2dpService).getDynamicBufferSupport();
    }

    @Test
    public void getBufferConstraints() {
        mBinder.getBufferConstraints(mAttributionSource);
        verify(mA2dpService).getBufferConstraints();
    }

    @Test
    public void setBufferLengthMillis() {
        int codec = 0;
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;

        mBinder.setBufferLengthMillis(codec, value, mAttributionSource);
        verify(mA2dpService).setBufferLengthMillis(codec, value);
    }
}
