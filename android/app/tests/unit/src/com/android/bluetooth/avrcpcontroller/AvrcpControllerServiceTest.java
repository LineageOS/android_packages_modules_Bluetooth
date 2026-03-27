/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerNativeInterface.RemoteFeatures;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.media_audio.sink.BluetoothMediaBrowserService.BrowseResult;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test cases for {@link AvrcpControllerService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Mock private A2dpSinkService mA2dpSinkService;
    @Mock private AdapterService mAdapterService;
    @Mock private AvrcpControllerStateMachine mStateMachine;
    @Mock private AvrcpControllerStateMachine mStateMachine2;
    @Mock private AvrcpControllerNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice1 = getTestDevice(89);
    private final BluetoothDevice mDevice2 = getTestDevice(41);
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private AvrcpControllerService mService = null;

    @Before
    public void setUp() throws Exception {
        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getResources()).when(mAdapterService).getResources();
        mockGetSystemService(mAdapterService, AudioManager.class);

        mService = new AvrcpControllerService(mAdapterService, mNativeInterface);
        doReturn(Optional.of(mA2dpSinkService)).when(mAdapterService).getA2dpSinkService();

        mService.mDeviceStateMap.put(mDevice1, mStateMachine);
        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);

        // Set up device and state machine under test
        mService.mDeviceStateMap.put(mDevice2, mStateMachine2);

        doReturn(true).when(mA2dpSinkService).setActiveDevice(any(BluetoothDevice.class));
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
    }

    @Test
    public void removeStateMachine() {
        doReturn(mDevice1).when(mStateMachine).getDevice();

        mService.removeStateMachine(mStateMachine);

        assertThat(mService.mDeviceStateMap).doesNotContainKey(mDevice1);
    }

    @Test
    public void getConnectedDevices() {
        doReturn(Set.of(mDevice1)).when(mAdapterService).getBondedDevices();
        doReturn(STATE_CONNECTED).when(mStateMachine).getState();

        assertThat(mService.getConnectedDevices()).contains(mDevice1);
    }

    @Test
    public void setActiveDevice_whenA2dpSinkServiceIsNotInitialized_returnsFalse() {
        doReturn(Optional.empty()).when(mAdapterService).getA2dpSinkService();
        assertThat(mService.setActiveDevice(mDevice1)).isFalse();
        assertThat(mService.getActiveDevice()).isNull();
    }

    @Test
    public void getCurrentMetadataIfNoCoverArt_doesNotCrash() {
        mService.getCurrentMetadataIfNoCoverArt(mDevice1);
    }

    @Test
    public void refreshContents() {
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        doReturn(mDevice1).when(node).getDevice();

        mService.refreshContents(node);

        verify(mStateMachine).requestContents(node);
    }

    @Test
    public void playItem() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        doReturn(node).when(mStateMachine).findNode(parentMediaId);

        mService.playItem(parentMediaId);

        verify(mStateMachine).playItem(node);
    }

    @Test
    public void getContents() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        doReturn(node).when(mStateMachine).findNode(parentMediaId);

        mService.getContents(parentMediaId);

        verify(node, atLeastOnce()).getContents();
    }

    /**
     * Pre-conditions: No node in BrowseTree for specified media ID Test: Call
     * AvrcpControllerService.getContents() Expected Output: BrowseResult object with status
     * ERROR_MEDIA_ID_INVALID
     */
    @Test
    public void testGetContentsNoNode_returnInvalidMediaIdStatus() {
        String parentMediaId = "test_parent_media_id";
        doReturn(null).when(mStateMachine).findNode(parentMediaId);
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.status()).isEqualTo(BrowseResult.ERROR_MEDIA_ID_INVALID);
    }

    /**
     * Pre-conditions: No device is connected - parent media ID is at the root of the BrowseTree
     * Test: Call AvrcpControllerService.getContents() Expected Output: BrowseResult object with
     * status NO_DEVICE_CONNECTED
     */
    @Test
    public void getContentsNoDeviceConnected_returnNoDeviceConnectedStatus() {
        String parentMediaId = BrowseTree.ROOT;
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.status()).isEqualTo(BrowseResult.NO_DEVICE_CONNECTED);
    }

    /**
     * Pre-conditions: At least one device is connected Test: Call
     * AvrcpControllerService.getContents() Expected Output: BrowseResult object with status SUCCESS
     */
    @Test
    public void getContentsOneDeviceConnected_returnSuccessStatus() {
        String parentMediaId = BrowseTree.ROOT;
        mService.getBrowseTree().onConnected(mDevice1);
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.status()).isEqualTo(BrowseResult.SUCCESS);
    }

    /**
     * Pre-conditions: Node for specified media ID is not cached Test: {@link
     * BrowseTree.BrowseNode#getContents} returns {@code null} when the node has no children/items
     * and the node is not cached. When {@link AvrcpControllerService#getContents} receives a node
     * that is not cached, it should interpret the status as `DOWNLOAD_PENDING`. Expected Output:
     * BrowseResult object with status DOWNLOAD_PENDING; verify that a download request has been
     * sent by checking if mStateMachine.requestContents() is called
     */
    @Test
    public void getContentsNodeNotCached_returnDownloadPendingStatus() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        doReturn(node).when(mStateMachine).findNode(parentMediaId);
        doReturn(false).when(node).isCached();
        doReturn(mDevice1).when(node).getDevice();
        doReturn(parentMediaId).when(node).getID();

        BrowseResult result = mService.getContents(parentMediaId);

        verify(mStateMachine).requestContents(eq(node));
        assertThat(result.status()).isEqualTo(BrowseResult.DOWNLOAD_PENDING);
    }

    /**
     * Pre-conditions: Parent media ID that is not BrowseTree.ROOT; isCached returns true Test: Call
     * AvrcpControllerService.getContents() Expected Output: BrowseResult object with status SUCCESS
     */
    @Test
    public void getContentsNoErrorConditions_returnsSuccessStatus() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        doReturn(node).when(mStateMachine).findNode(parentMediaId);
        doReturn(new ArrayList<>(0)).when(node).getContents();
        doReturn(true).when(node).isCached();

        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.status()).isEqualTo(BrowseResult.SUCCESS);
    }

    @Test
    public void onChangeFolderResponse() {
        int count = 1;

        mService.onChangeFolderResponse(mDevice1, count);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_FOLDER_PATH, count);
    }

    @Test
    public void onSetBrowsedPlayerResponse() {
        int items = 3;
        int depth = 5;

        mService.onSetBrowsedPlayerResponse(mDevice1, items, depth);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_BROWSED_PLAYER,
                        items,
                        depth);
    }

    @Test
    public void onSetAddressedPlayerResponse() {
        int status = 1;

        mService.onSetAddressedPlayerResponse(mDevice1, status);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ADDRESSED_PLAYER);
    }

    @Test
    public void onAddressedPlayerChanged() {
        int id = 1;

        mService.onAddressedPlayerChanged(mDevice1, id);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, id);
    }

    @Test
    public void onNowPlayingContentChanged() {
        mService.onNowPlayingContentChanged(mDevice1);

        verify(mStateMachine).nowPlayingContentChanged();
    }

    @Test
    public void onConnectionStateChanged_connectCase() {
        boolean remoteControlConnected = true;
        boolean browsingConnected = true; // Calls connect when any of them is true.

        mService.onConnectionStateChanged(remoteControlConnected, browsingConnected, mDevice1);

        verify(mStateMachine).connect(eq(remoteControlConnected), eq(browsingConnected));
    }

    @Test
    public void onConnectionStateChanged_disconnectCase() {
        boolean remoteControlConnected = false;
        boolean browsingConnected = false; // Calls disconnect when both of them are false.

        mService.onConnectionStateChanged(remoteControlConnected, browsingConnected, mDevice1);
        verify(mStateMachine).disconnect();
    }

    @Test
    public void onCoverArtPsmReceived() {
        int psm = 1;

        mService.onCoverArtPsmReceived(mDevice1, psm);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM, psm);
    }

    @Test
    public void onRemoteFeaturesChanged() {
        RemoteFeatures features = new RemoteFeatures(true, true, true, true);

        mService.onRemoteFeaturesChanged(mDevice1, features);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_RECEIVED_REMOTE_FEATURES,
                        features);
    }

    @Test
    public void onRegisterAbsoluteVolumeNotification() {
        byte label = 1;

        mService.onRegisterAbsoluteVolumeNotification(mDevice1, label);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION,
                        label);
    }

    @Test
    public void onSetAbsoluteVolumeRequest() {
        byte absVol = 15;
        byte label = 1;

        mService.onSetAbsoluteVolumeRequest(mDevice1, absVol, label);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol, label);
    }

    @Test
    public void onTrackChanged() {
        byte numAttrs = 0;
        int[] attrs = new int[0];
        String[] attrVals = new String[0];

        mService.onTrackChanged(mDevice1, numAttrs, attrs, attrVals);

        ArgumentCaptor<AvrcpItem> captor = ArgumentCaptor.forClass(AvrcpItem.class);
        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_TRACK_CHANGED),
                        captor.capture());
        AvrcpItem item = captor.getValue();
        assertThat(item.getDevice()).isEqualTo(mDevice1);
        assertThat(item.getItemType()).isEqualTo(AvrcpItem.TYPE_MEDIA);
        assertThat(item.getUuid()).isNotNull(); // Random uuid
    }

    @Test
    public void onPlaybackPositionChanged() {
        int songLen = 100;
        int currSongPos = 33;

        mService.onPlaybackPositionChanged(mDevice1, songLen, currSongPos);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_POS_CHANGED,
                        songLen,
                        currSongPos);
    }

    @Test
    public void onPlaybackStatusChanged() {
        byte status = PlaybackStateCompat.STATE_REWINDING;

        mService.onPlaybackStatusChanged(mDevice1, status);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                        PlaybackStateCompat.STATE_REWINDING);
    }

    @Test
    public void onPlayerAppSettingChanged() {
        byte[] playerAttribRsp =
                new byte[] {
                    PlayerApplicationSettings.REPEAT_STATUS,
                    PlayerApplicationSettings.JNI_REPEAT_STATUS_ALL_TRACK_REPEAT
                };

        mService.onPlayerAppSettingChanged(mDevice1, playerAttribRsp, 2);

        verify(mStateMachine)
                .sendMessage(
                        eq(
                                AvrcpControllerStateMachine
                                        .MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS),
                        any(PlayerApplicationSettings.class));
    }

    @Test
    public void onAvailablePlayersChanged() {
        mService.onAvailablePlayersChanged(mDevice1);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);
    }

    @Test
    public void onGetFolderItemsResponse() {
        int status = 2;
        AvrcpItem[] items = new AvrcpItem[] {mock(AvrcpItem.class)};

        mService.onGetFolderItemsResponse(mDevice1, status, items);

        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS),
                        eq(new ArrayList<>(Arrays.asList(items))));
    }

    @Test
    public void onGetPlayerItemsResponse() {
        List<AvrcpPlayer> items = List.of(mock(AvrcpPlayer.class));

        mService.onGetPlayerItemsResponse(mDevice1, items);

        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS),
                        eq(items));
    }

    @Test
    public void dump_doesNotCrash() {
        mService.onCoverArtPsmReceived(mDevice1, 1);
        mService.dump(new StringBuilder());
    }

    @Test
    public void testOnFocusChange_audioGainDeviceActive_sessionActivated() {
        mService.setActiveDevice(mDevice1);
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN);
        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.AUDIO_FOCUS_STATE_CHANGE),
                        eq(AudioManager.AUDIOFOCUS_GAIN));
    }

    @Test
    public void testOnFocusChange_audioLoss_sessionDeactivated() {
        mService.setActiveDevice(mDevice1);
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS);
        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.AUDIO_FOCUS_STATE_CHANGE),
                        eq(AudioManager.AUDIOFOCUS_LOSS));
    }

    /**
     * Connect first device and check that it is the active device. Pair a second device, then
     * disconnect and repair this known second device. Confirm that audio focus is maintained by
     * first device by checking that it has remained as the active device.
     */
    @Test
    public void testActiveDeviceMaintainsAudioFocusWhenOtherDeviceConnects_audioFocusMaintained() {
        mService.onConnectionStateChanged(true, true, mDevice1);
        // check set active device is called
        verify(mA2dpSinkService).setActiveDevice(mDevice1);
        doReturn(mDevice1).when(mA2dpSinkService).getActiveDevice();

        // connect another phone
        mService.onConnectionStateChanged(true, true, mDevice2);
        verify(mA2dpSinkService, times(0)).setActiveDevice(mDevice2);

        // disconnect and reconnect other phone
        mService.onConnectionStateChanged(false, false, mDevice2);
        mService.onConnectionStateChanged(true, true, mDevice2);
        verify(mA2dpSinkService, times(0)).setActiveDevice(mDevice2);
    }
}
