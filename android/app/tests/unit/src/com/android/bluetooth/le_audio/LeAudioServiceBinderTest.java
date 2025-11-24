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

package com.android.bluetooth.le_audio;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.UUID;

/** Test cases for {@link LeAudioServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LeAudioServiceBinderTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private LeAudioService mService;

    private static final String TEST_BROADCAST_NAME = "TEST";
    private static final int TEST_QUALITY = BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD;

    private LeAudioServiceBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new LeAudioServiceBinder(mService);
    }

    @Test
    public void connect() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.connect(device, mSource);
        verify(mService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.disconnect(device, mSource);
        verify(mService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {

        mBinder.getConnectedDevices(mSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getConnectedGroupLeadDevice() {
        int groupId = 1;

        mBinder.getConnectedGroupLeadDevice(groupId, mSource);
        verify(mService).getConnectedGroupLeadDevice(groupId);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_DISCONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.getConnectionState(device, mSource);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void setActiveDevice() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.setActiveDevice(device, mSource);
        verify(mService).setActiveDevice(device);
    }

    @Test
    public void setActiveDevice_withNullDevice_callsRemoveActiveDevice() {

        mBinder.setActiveDevice(null, mSource);
        verify(mService).removeActiveDevice(true);
    }

    @Test
    public void getActiveDevices() {

        mBinder.getActiveDevices(mSource);
        verify(mService).getActiveDevices();
    }

    @Test
    public void getAudioLocation() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.getAudioLocation(device, mSource);
        verify(mService).getAudioLocation(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);
        int connectionPolicy = CONNECTION_POLICY_UNKNOWN;

        mBinder.setConnectionPolicy(device, connectionPolicy, mSource);
        verify(mService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.getConnectionPolicy(device, mSource);
        verify(mService).getConnectionPolicy(device);
    }

    @Test
    public void setCcidInformation() {
        ParcelUuid uuid = new ParcelUuid(new UUID(0, 0));
        int ccid = 0;
        int contextType = BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED;

        mBinder.setCcidInformation(uuid, ccid, contextType, mSource);
        verify(mService).setCcidInformation(uuid, ccid, contextType);
    }

    @Test
    public void getGroupId() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.getGroupId(device, mSource);
        verify(mService).getGroupId(device);
    }

    @Test
    public void groupAddNode() {
        int groupId = 1;
        BluetoothDevice device = getTestDevice(0);

        mBinder.groupAddNode(groupId, device, mSource);
        verify(mService).groupAddNode(groupId, device);
    }

    @Test
    public void setInCall() {
        boolean inCall = true;

        mBinder.setInCall(inCall, mSource);
        verify(mService).setInCall(inCall);
    }

    @Test
    public void setInactiveForHfpHandover() {
        BluetoothDevice device = getTestDevice(0);

        mBinder.setInactiveForHfpHandover(device, mSource);
        verify(mService).setInactiveForHfpHandover(device);
    }

    @Test
    public void groupRemoveNode() {
        int groupId = 1;
        BluetoothDevice device = getTestDevice(0);

        mBinder.groupRemoveNode(groupId, device, mSource);
        verify(mService).groupRemoveNode(groupId, device);
    }

    @Test
    public void setVolume() {
        int volume = 3;

        mBinder.setVolume(volume, mSource);
        verify(mService).setVolume(volume);
    }

    @Test
    public void registerUnregisterCallback() {
        IBluetoothLeAudioCallback callback = Mockito.mock(IBluetoothLeAudioCallback.class);

        mBinder.registerCallback(callback, mSource);
        verify(mService).registerCallback(callback);

        mBinder.unregisterCallback(callback, mSource);
        verify(mService).unregisterCallback(callback);
    }

    @Test
    public void registerUnregisterLeBroadcastCallback() {
        IBluetoothLeBroadcastCallback callback = Mockito.mock(IBluetoothLeBroadcastCallback.class);

        mBinder.registerLeBroadcastCallback(callback, mSource);
        verify(mService).registerLeBroadcastCallback(callback);

        mBinder.unregisterLeBroadcastCallback(callback, mSource);
        verify(mService).unregisterLeBroadcastCallback(callback);
    }

    @Test
    public void startBroadcast() {
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();

        mBinder.startBroadcast(broadcastSettings, mSource);
        verify(mService).createBroadcast(broadcastSettings);
    }

    @Test
    public void stopBroadcast() {
        int id = 1;

        mBinder.stopBroadcast(id, mSource);
        verify(mService).stopBroadcast(id);
    }

    @Test
    public void updateBroadcast() {
        int id = 1;
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();

        mBinder.updateBroadcast(id, broadcastSettings, mSource);
        verify(mService).updateBroadcast(id, broadcastSettings);
    }

    @Test
    public void isPlaying() {
        int id = 1;

        mBinder.isPlaying(id, mSource);
        verify(mService).isPlaying(id);
    }

    @Test
    public void getAllBroadcastMetadata() {

        mBinder.getAllBroadcastMetadata(mSource);
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

        mBinder.getCodecStatus(groupId, mSource);
        verify(mService).getCodecStatus(groupId);
    }

    @Test
    public void setCodecConfigPreference() {
        int groupId = 1;
        BluetoothLeAudioCodecConfig inputConfig = new BluetoothLeAudioCodecConfig.Builder().build();
        BluetoothLeAudioCodecConfig outputConfig =
                new BluetoothLeAudioCodecConfig.Builder().build();

        mBinder.setCodecConfigPreference(groupId, inputConfig, outputConfig, mSource);
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
