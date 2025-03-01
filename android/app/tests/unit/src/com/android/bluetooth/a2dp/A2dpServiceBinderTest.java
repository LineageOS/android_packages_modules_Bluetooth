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

package com.android.bluetooth.a2dp;

import static android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

public class A2dpServiceBinderTest {
    private static final AttributionSource sSource = new AttributionSource.Builder(0).build();
    private static final BluetoothDevice sDevice = getTestDevice(0);

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpService mA2dpService;
    @Mock private PackageManager mPackageManager;

    private A2dpService.BluetoothA2dpBinder mBinder;

    @Before
    public void setUp() throws Exception {
        doReturn(mPackageManager).when(mA2dpService).getPackageManager();
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
        doReturn(appInfo).when(mPackageManager).getApplicationInfo(any(), anyInt());

        mBinder = new A2dpService.BluetoothA2dpBinder(mA2dpService);
    }

    @After
    public void cleanUp() {
        mBinder.cleanup();
    }

    @Test
    public void connect() {
        mBinder.connect(sDevice, sSource);
        verify(mA2dpService).connect(sDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(sDevice, sSource);
        verify(mA2dpService).disconnect(sDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(sSource);
        verify(mA2dpService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, sSource);
        verify(mA2dpService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(sDevice, sSource);
        verify(mA2dpService).getConnectionState(sDevice);
    }

    @Test
    public void setActiveDevice() {
        mBinder.setActiveDevice(sDevice, sSource);
        verify(mA2dpService).setActiveDevice(sDevice);
    }

    @Test
    public void setActiveDevice_withNull_callsRemoveActiveDevice() {
        mBinder.setActiveDevice(null, sSource);
        verify(mA2dpService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevice() {
        mBinder.getActiveDevice(sSource);
        verify(mA2dpService).getActiveDevice();
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;

        mBinder.setConnectionPolicy(sDevice, connectionPolicy, sSource);
        verify(mA2dpService).setConnectionPolicy(sDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(sDevice, sSource);
        verify(mA2dpService).getConnectionPolicy(sDevice);
    }

    @Test
    public void setAvrcpAbsoluteVolume() {
        int volume = 3;

        mBinder.setAvrcpAbsoluteVolume(volume, sSource);
        verify(mA2dpService).setAvrcpAbsoluteVolume(volume);
    }

    @Test
    public void isA2dpPlaying() {
        mBinder.isA2dpPlaying(sDevice, sSource);
        verify(mA2dpService).isA2dpPlaying(sDevice);
    }

    @Test
    public void getCodecStatus() {
        mBinder.getCodecStatus(sDevice, sSource);
        verify(mA2dpService).getCodecStatus(sDevice);
    }

    @Test
    public void setCodecConfigPreference() {
        BluetoothCodecConfig config = new BluetoothCodecConfig(SOURCE_CODEC_TYPE_INVALID);

        mBinder.setCodecConfigPreference(sDevice, config, sSource);
        verify(mA2dpService).setCodecConfigPreference(sDevice, config);
    }

    @Test
    public void enableOptionalCodecs() {

        mBinder.enableOptionalCodecs(sDevice, sSource);
        verify(mA2dpService).enableOptionalCodecs(sDevice);
    }

    @Test
    public void disableOptionalCodecs() {

        mBinder.disableOptionalCodecs(sDevice, sSource);
        verify(mA2dpService).disableOptionalCodecs(sDevice);
    }

    @Test
    public void isOptionalCodecsSupported() {
        mBinder.isOptionalCodecsSupported(sDevice, sSource);
        verify(mA2dpService).getSupportsOptionalCodecs(sDevice);
    }

    @Test
    public void isOptionalCodecsEnabled() {
        mBinder.isOptionalCodecsEnabled(sDevice, sSource);
        verify(mA2dpService).getOptionalCodecsEnabled(sDevice);
    }

    @Test
    public void setOptionalCodecsEnabled() {
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;

        mBinder.setOptionalCodecsEnabled(sDevice, value, sSource);
        verify(mA2dpService).setOptionalCodecsEnabled(sDevice, value);
    }

    @Test
    public void getDynamicBufferSupport() {
        mBinder.getDynamicBufferSupport(sSource);
        verify(mA2dpService).getDynamicBufferSupport();
    }

    @Test
    public void getBufferConstraints() {
        mBinder.getBufferConstraints(sSource);
        verify(mA2dpService).getBufferConstraints();
    }

    @Test
    public void setBufferLengthMillis() {
        int codec = 0;
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;

        mBinder.setBufferLengthMillis(codec, value, sSource);
        verify(mA2dpService).setBufferLengthMillis(codec, value);
    }
}
