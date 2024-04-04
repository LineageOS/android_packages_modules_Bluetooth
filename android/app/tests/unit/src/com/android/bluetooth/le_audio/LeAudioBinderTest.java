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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.InstrumentationRegistry;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.AudioRoutingManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LeAudioBinderTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private LeAudioService mLeAudioService;
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mAdapterService;
    @Mock private LeAudioNativeInterface mNativeInterface;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private AudioRoutingManager mAudioRoutingManager;

    @Mock private RemoteCallbackList<IBluetoothLeAudioCallback> mLeAudioCallbacks;
    @Mock private RemoteCallbackList<IBluetoothLeBroadcastCallback> mBroadcastCallbacks;

    private LeAudioService.BluetoothLeAudioBinder mBinder;
    private BluetoothAdapter mAdapter;

    private static final String TEST_BROADCAST_NAME = "TEST";
    private static final int TEST_QUALITY =
            BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD;

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mAudioRoutingManager).when(mAdapterService).getActiveDeviceManager();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(true);
        doReturn(future).when(mAudioRoutingManager).activateDeviceProfile(any(), anyInt());

        mLeAudioService =
                spy(
                        new LeAudioService(
                                InstrumentationRegistry.getTargetContext(), mNativeInterface));
        mLeAudioService.start();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBinder = new LeAudioService.BluetoothLeAudioBinder(mLeAudioService);
        mLeAudioService.mLeAudioCallbacks = mLeAudioCallbacks;
        mLeAudioService.mBroadcastCallbacks = mBroadcastCallbacks;
    }

    @After
    public void cleanUp() {
        mBinder.cleanup();
        mLeAudioService.stop();
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void connect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.connect(device, source);
        verify(mLeAudioService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.disconnect(device, source);
        verify(mLeAudioService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectedDevices(source);
        verify(mLeAudioService).getConnectedDevices();
    }

    @Test
    public void getConnectedGroupLeadDevice() {
        int groupId = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectedGroupLeadDevice(groupId, source);
        verify(mLeAudioService).getConnectedGroupLeadDevice(groupId);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {BluetoothProfile.STATE_DISCONNECTED };
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getDevicesMatchingConnectionStates(states, source);
        verify(mLeAudioService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionState(device, source);
        verify(mLeAudioService).getConnectionState(device);
    }

    @Test
    public void setActiveDevice() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mSetFlagsRule.disableFlags(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION);
        mBinder.setActiveDevice(device, source);
        verify(mLeAudioService).setActiveDevice(device);

        mSetFlagsRule.enableFlags(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION);
        mBinder.setActiveDevice(device, source);
        verify(mAudioRoutingManager).activateDeviceProfile(device, BluetoothProfile.LE_AUDIO);
    }

    @Test
    public void setActiveDevice_withNullDevice_callsRemoveActiveDevice() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mSetFlagsRule.disableFlags(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION);
        mBinder.setActiveDevice(null, source);
        verify(mLeAudioService).removeActiveDevice(true);

        mSetFlagsRule.enableFlags(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION);
        mBinder.setActiveDevice(null, source);
        verify(mAudioRoutingManager).activateDeviceProfile(null, BluetoothProfile.LE_AUDIO);
    }

    @Test
    public void getActiveDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getActiveDevices(source);
        verify(mLeAudioService).getActiveDevices();
    }

    @Test
    public void getAudioLocation() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAudioLocation(device, source);
        verify(mLeAudioService).getAudioLocation(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        int connectionPolicy = BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setConnectionPolicy(device, connectionPolicy, source);
        verify(mLeAudioService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionPolicy(device, source);
        verify(mLeAudioService).getConnectionPolicy(device);
    }

    @Test
    public void setCcidInformation() {
        ParcelUuid uuid = new ParcelUuid(new UUID(0, 0));
        int ccid = 0;
        int contextType = BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setCcidInformation(uuid, ccid, contextType, source);
        verify(mLeAudioService).setCcidInformation(uuid, ccid, contextType);
    }

    @Test
    public void getGroupId() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getGroupId(device, source);
        verify(mLeAudioService).getGroupId(device);
    }

    @Test
    public void groupAddNode() {
        int groupId = 1;
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.groupAddNode(groupId, device, source);
        verify(mLeAudioService).groupAddNode(groupId, device);
    }

    @Test
    public void setInCall() {
        boolean inCall = true;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setInCall(inCall, source);
        verify(mLeAudioService).setInCall(inCall);
    }

    @Test
    public void setInactiveForHfpHandover() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setInactiveForHfpHandover(device, source);
        verify(mLeAudioService).setInactiveForHfpHandover(device);
    }

    @Test
    public void groupRemoveNode() {
        int groupId = 1;
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.groupRemoveNode(groupId, device, source);
        verify(mLeAudioService).groupRemoveNode(groupId, device);
    }

    @Test
    public void setVolume() {
        int volume = 3;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setVolume(volume, source);
        verify(mLeAudioService).setVolume(volume);
    }

    @Test
    public void registerCallback() {
        IBluetoothLeAudioCallback callback = Mockito.mock(IBluetoothLeAudioCallback.class);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.registerCallback(callback, source);
        verify(mLeAudioService.mLeAudioCallbacks).register(callback);
    }

    @Test
    public void unregisterCallback() {
        IBluetoothLeAudioCallback callback = Mockito.mock(IBluetoothLeAudioCallback.class);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.unregisterCallback(callback, source);
        verify(mLeAudioService.mLeAudioCallbacks).unregister(callback);
    }

    @Test
    public void registerLeBroadcastCallback() {
        IBluetoothLeBroadcastCallback callback = Mockito.mock(IBluetoothLeBroadcastCallback.class);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.registerLeBroadcastCallback(callback, source);
        verify(mLeAudioService.mBroadcastCallbacks).register(callback);
    }

    @Test
    public void unregisterLeBroadcastCallback() {
        IBluetoothLeBroadcastCallback callback = Mockito.mock(IBluetoothLeBroadcastCallback.class);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.unregisterLeBroadcastCallback(callback, source);
        verify(mLeAudioService.mBroadcastCallbacks).unregister(callback);
    }

    @Test
    public void startBroadcast() {
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.startBroadcast(broadcastSettings, source);
        verify(mLeAudioService).createBroadcast(broadcastSettings);
    }

    @Test
    public void stopBroadcast() {
        int id = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.stopBroadcast(id, source);
        verify(mLeAudioService).stopBroadcast(id);
    }

    @Test
    public void updateBroadcast() {
        int id = 1;
        BluetoothLeBroadcastSettings broadcastSettings = buildBroadcastSettingsFromMetadata();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.updateBroadcast(id, broadcastSettings, source);
        verify(mLeAudioService).updateBroadcast(id, broadcastSettings);
    }

    @Test
    public void isPlaying() {
        int id = 1;
        BluetoothLeAudioContentMetadata metadata =
                new BluetoothLeAudioContentMetadata.Builder().build();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.isPlaying(id, source);
        verify(mLeAudioService).isPlaying(id);
    }

    @Test
    public void getAllBroadcastMetadata() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAllBroadcastMetadata(source);
        verify(mLeAudioService).getAllBroadcastMetadata();
    }

    @Test
    public void getMaximumNumberOfBroadcasts() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getMaximumNumberOfBroadcasts(source);
        verify(mLeAudioService).getMaximumNumberOfBroadcasts();
    }

    @Test
    public void getMaximumStreamsPerBroadcast() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getMaximumStreamsPerBroadcast(source);
        verify(mLeAudioService).getMaximumStreamsPerBroadcast();
    }

    @Test
    public void getMaximumSubgroupsPerBroadcast() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getMaximumSubgroupsPerBroadcast(source);
        verify(mLeAudioService).getMaximumSubgroupsPerBroadcast();
    }

    @Test
    public void getCodecStatus() {
        int groupId = 1;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getCodecStatus(groupId, source);
        verify(mLeAudioService).getCodecStatus(groupId);
    }

    @Test
    public void setCodecConfigPreference() {
        int groupId = 1;
        BluetoothLeAudioCodecConfig inputConfig =
                new BluetoothLeAudioCodecConfig.Builder().build();
        BluetoothLeAudioCodecConfig outputConfig =
                new BluetoothLeAudioCodecConfig.Builder().build();
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setCodecConfigPreference(groupId, inputConfig, outputConfig, source);
        verify(mLeAudioService).setCodecConfigPreference(groupId, inputConfig, outputConfig);
    }

    private BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata() {
        BluetoothLeAudioContentMetadata metadata =
                new BluetoothLeAudioContentMetadata.Builder().build();

        BluetoothLeAudioContentMetadata publicBroadcastMetadata =
                new BluetoothLeAudioContentMetadata.Builder().build();

        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                .setPreferredQuality(TEST_QUALITY)
                .setContentMetadata(metadata);

        BluetoothLeBroadcastSettings.Builder builder = new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(false)
                        .setBroadcastName(TEST_BROADCAST_NAME)
                        .setBroadcastCode(null)
                        .setPublicBroadcastMetadata(publicBroadcastMetadata);
        // builder expect at least one subgroup setting
        builder.addSubgroupSettings(subgroupBuilder.build());
        return builder.build();
    }
}
