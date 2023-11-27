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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BufferConstraints;
import android.content.AttributionSource;
import android.content.Context;

import androidx.test.InstrumentationRegistry;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.AudioRoutingManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.FakeFeatureFlagsImpl;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.x.com.android.modules.utils.SynchronousResultReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

public class A2dpServiceBinderTest {
    private A2dpService mA2dpService;
    private FakeFeatureFlagsImpl mFakeFlagsImpl;
    @Mock private AdapterService mAdapterService;
    @Mock private A2dpNativeInterface mNativeInterface;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private AudioRoutingManager mAudioRoutingManager;
    private A2dpService.BluetoothA2dpBinder mBinder;
    private BluetoothAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mAudioRoutingManager).when(mAdapterService).getActiveDeviceManager();

        Context context = InstrumentationRegistry.getTargetContext();
        mFakeFlagsImpl = new FakeFeatureFlagsImpl();
        mA2dpService = spy(new A2dpService(context, mNativeInterface, mFakeFlagsImpl));
        mA2dpService.doStart();

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBinder = new A2dpService.BluetoothA2dpBinder(mA2dpService);
    }

    @After
    public void cleaUp() {
        mBinder.cleanup();
        mA2dpService.doStop();
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void connect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.connect(device, source, recv);
        verify(mA2dpService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.disconnect(device, source, recv);
        verify(mA2dpService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                SynchronousResultReceiver.get();

        mBinder.getConnectedDevices(source, recv);
        verify(mA2dpService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {BluetoothProfile.STATE_CONNECTED };
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                SynchronousResultReceiver.get();

        mBinder.getDevicesMatchingConnectionStates(states, source, recv);
        verify(mA2dpService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                SynchronousResultReceiver.get();

        mBinder.getConnectionState(device, source, recv);
        verify(mA2dpService).getConnectionState(device);
    }

    @Test
    public void setActiveDevice() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mFakeFlagsImpl.setFlag(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION, false);
        SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
        mBinder.setActiveDevice(device, source, recv);
        verify(mA2dpService).setActiveDevice(device);

        mFakeFlagsImpl.setFlag(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION, true);
        recv = SynchronousResultReceiver.get();
        mBinder.setActiveDevice(device, source, recv);
        verify(mAudioRoutingManager).activateDeviceProfile(device, BluetoothProfile.A2DP, recv);
    }

    @Test
    public void setActiveDevice_withNull_callsRemoveActiveDevice() {
        BluetoothDevice device = null;
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.setActiveDevice(device, source, recv);
        verify(mA2dpService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevice() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<BluetoothDevice> recv = SynchronousResultReceiver.get();

        mBinder.getActiveDevice(source, recv);
        verify(mA2dpService).getActiveDevice();
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        int connectionPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED;
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.setConnectionPolicy(device, connectionPolicy, source, recv);
        verify(mA2dpService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();

        mBinder.getConnectionPolicy(device, source, recv);
        verify(mA2dpService).getConnectionPolicy(device);
    }

    @Test
    public void setAvrcpAbsoluteVolume() {
        int volume = 3;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setAvrcpAbsoluteVolume(volume, source);
        verify(mA2dpService).setAvrcpAbsoluteVolume(volume);
    }

    @Test
    public void isA2dpPlaying() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.isA2dpPlaying(device, source, recv);
        verify(mA2dpService).isA2dpPlaying(device);
    }

    @Test
    public void getCodecStatus() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<BluetoothCodecStatus> recv =
                SynchronousResultReceiver.get();

        mBinder.getCodecStatus(device, source, recv);
        verify(mA2dpService).getCodecStatus(device);
    }

    @Test
    public void setCodecConfigPreference() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        BluetoothCodecConfig config = new BluetoothCodecConfig(SOURCE_CODEC_TYPE_INVALID);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setCodecConfigPreference(device, config, source);
        verify(mA2dpService).setCodecConfigPreference(device, config);
    }

    @Test
    public void enableOptionalCodecs() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.enableOptionalCodecs(device, source);
        verify(mA2dpService).enableOptionalCodecs(device);
    }

    @Test
    public void disableOptionalCodecs() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.disableOptionalCodecs(device, source);
        verify(mA2dpService).disableOptionalCodecs(device);
    }

    @Test
    public void isOptionalCodecsSupported() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();

        mBinder.isOptionalCodecsSupported(device, source, recv);
        verify(mA2dpService).getSupportsOptionalCodecs(device);
    }

    @Test
    public void isOptionalCodecsEnabled() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();

        mBinder.isOptionalCodecsEnabled(device, source, recv);
        verify(mA2dpService).getOptionalCodecsEnabled(device);
    }

    @Test
    public void setOptionalCodecsEnabled() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setOptionalCodecsEnabled(device, value, source);
        verify(mA2dpService).setOptionalCodecsEnabled(device, value);
    }

    @Test
    public void getDynamicBufferSupport() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();

        mBinder.getDynamicBufferSupport(source, recv);
        verify(mA2dpService).getDynamicBufferSupport();
    }

    @Test
    public void getBufferConstraints() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<BufferConstraints> recv = SynchronousResultReceiver.get();

        mBinder.getBufferConstraints(source, recv);
        verify(mA2dpService).getBufferConstraints();
    }

    @Test
    public void setBufferLengthMillis() {
        int codec = 0;
        int value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
        AttributionSource source = new AttributionSource.Builder(0).build();
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();

        mBinder.setBufferLengthMillis(codec, value, source, recv);
        verify(mA2dpService).setBufferLengthMillis(codec, value);
    }
}
