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

package com.android.bluetooth.le_audio;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.UUID;

public class LeAudioBinderTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private LeAudioService mService;

    private static final String TEST_BROADCAST_NAME = "TEST";
    private static final int TEST_QUALITY = BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD;

    private LeAudioService.BluetoothLeAudioBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new LeAudioService.BluetoothLeAudioBinder(mService);
    }

    @Test
    public void connect() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.connect(device, source);
        verify(mService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.disconnect(device, source);
        verify(mService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectedDevices(source);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getConnectedGroupLeadDevice() {
        int groupId = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectedGroupLeadDevice(groupId, source);
        verify(mService).getConnectedGroupLeadDevice(groupId);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_DISCONNECTED};
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getDevicesMatchingConnectionStates(states, source);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionState(device, source);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void setActiveDevice() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setActiveDevice(device, source);
        verify(mService).setActiveDevice(device);
    }

    @Test
    public void setActiveDevice_withNullDevice_callsRemoveActiveDevice() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setActiveDevice(null, source);
        verify(mService).removeActiveDevice(true);
    }

    @Test
    public void getActiveDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getActiveDevices(source);
        verify(mService).getActiveDevices();
    }

    @Test
    public void getAudioLocation() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAudioLocation(device, source);
        verify(mService).getAudioLocation(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);
        int connectionPolicy = CONNECTION_POLICY_UNKNOWN;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setConnectionPolicy(device, connectionPolicy, source);
        verify(mService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionPolicy(device, source);
        verify(mService).getConnectionPolicy(device);
    }

    @Test
    public void setCcidInformation() {
        ParcelUuid uuid = new ParcelUuid(new UUID(0, 0));
        int ccid = 0;
        int contextType = BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setCcidInformation(uuid, ccid, contextType, source);
        verify(mService).setCcidInformation(uuid, ccid, contextType);
    }

    @Test
    public void getGroupId() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getGroupId(device, source);
        verify(mService).getGroupId(device);
    }

    @Test
    public void groupAddNode() {
        int groupId = 1;
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.groupAddNode(groupId, device, source);
        verify(mService).groupAddNode(groupId, device);
    }

    @Test
    public void setInCall() {
        boolean inCall = true;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setInCall(inCall, source);
        verify(mService).setInCall(inCall);
    }

    @Test
    public void setInactiveForHfpHandover() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setInactiveForHfpHandover(device, source);
        verify(mService).setInactiveForHfpHandover(device);
    }

    @Test
    public void groupRemoveNode() {
        int groupId = 1;
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.groupRemoveNode(groupId, device, source);
        verify(mService).groupRemoveNode(groupId, device);
    }

    @Test
    public void setVolume() {
        int volume = 3;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setVolume(volume, source);
        verify(mService).setVolume(volume);
    }

    @Test
    public void registerUnregisterCallback() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        IBluetoothLeAudioCallback callback = Mockito.mock(IBluetoothLeAudioCallback.class);

        mBinder.registerCallback(callback, source);
        verify(mService).registerCallback(callback);

        mBinder.unregisterCallback(callback, source);
        verify(mService).unregisterCallback(callback);
    }

    @Test
    public void registerUnregisterLeBroadcastCallback() {
        AttributionSource source = new AttributionSource.Builder(0).build();
        IBluetoothLeBroadcastCallback callback = Mockito.mock(IBluetoothLeBroadcastCallback.class);

        mBinder.registerLeBroadcastCallback(callback, source);
        verify(mService).registerLeBroadcastCallback(callback);

        mBinder.unregisterLeBroadcastCallback(callback, source);
        verify(mService).unregisterLeBroadcastCallback(callback);
    }

    @Test
    public void startBroadcast() {
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.startBroadcast(broadcastSettings, source);
        verify(mService).createBroadcast(broadcastSettings);
    }

    @Test
    public void stopBroadcast() {
        int id = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.stopBroadcast(id, source);
        verify(mService).stopBroadcast(id);
    }

    @Test
    public void updateBroadcast() {
        int id = 1;
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.updateBroadcast(id, broadcastSettings, source);
        verify(mService).updateBroadcast(id, broadcastSettings);
    }

    @Test
    public void isPlaying() {
        int id = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.isPlaying(id, source);
        verify(mService).isPlaying(id);
    }

    @Test
    public void getAllBroadcastMetadata() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAllBroadcastMetadata(source);
        verify(mService).getAllBroadcastMetadata();
    }

    @Test
    public void getMaximumNumberOfBroadcasts() {
        mBinder.getMaximumNumberOfBroadcasts();
        verify(mService).getMaximumNumberOfBroadcasts();
    }

    @Test
    public void getMaximumStreamsPerBroadcast() {
        mBinder.getMaximumStreamsPerBroadcast();
        verify(mService).getMaximumStreamsPerBroadcast();
    }

    @Test
    public void getMaximumSubgroupsPerBroadcast() {
        mBinder.getMaximumSubgroupsPerBroadcast();
        verify(mService).getMaximumSubgroupsPerBroadcast();
    }

    @Test
    public void getCodecStatus() {
        int groupId = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getCodecStatus(groupId, source);
        verify(mService).getCodecStatus(groupId);
    }

    @Test
    public void setCodecConfigPreference() {
        int groupId = 1;
        BluetoothLeAudioCodecConfig inputConfig = new BluetoothLeAudioCodecConfig.Builder().build();
        BluetoothLeAudioCodecConfig outputConfig =
                new BluetoothLeAudioCodecConfig.Builder().build();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setCodecConfigPreference(groupId, inputConfig, outputConfig, source);
        verify(mService).setCodecConfigPreference(groupId, inputConfig, outputConfig);
    }

    private static BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata() {
        BluetoothLeAudioContentMetadata metadata =
                new BluetoothLeAudioContentMetadata.Builder().build();

        BluetoothLeAudioContentMetadata publicBroadcastMetadata =
                new BluetoothLeAudioContentMetadata.Builder().build();

        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                        .setPreferredQuality(TEST_QUALITY)
                        .setContentMetadata(metadata);

        return new BluetoothLeBroadcastSettings.Builder()
                .setPublicBroadcast(false)
                .setBroadcastName(TEST_BROADCAST_NAME)
                .setBroadcastCode(null)
                .setPublicBroadcastMetadata(publicBroadcastMetadata)
                .addSubgroupSettings(subgroupBuilder.build())
                .build();
    }
}
