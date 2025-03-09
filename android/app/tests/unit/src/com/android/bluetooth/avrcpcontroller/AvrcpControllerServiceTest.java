/*
 * Copyright 2018 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService.BrowseResult;
import com.android.bluetooth.btservice.AdapterService;

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

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpSinkService mA2dpSinkService;
    @Mock private AdapterService mAdapterService;
    @Mock private AvrcpControllerStateMachine mStateMachine;
    @Mock private AvrcpControllerStateMachine mStateMachine2;
    @Mock private AvrcpControllerNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(89);
    private final BluetoothDevice mDevice2 = getTestDevice(41);
    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private AvrcpControllerService mService = null;

    @Before
    public void setUp() throws Exception {
        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mTargetContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mTargetContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mTargetContext.getResources()).when(mAdapterService).getResources();
        mockGetSystemService(mAdapterService, Context.AUDIO_SERVICE, AudioManager.class);

        mService = new AvrcpControllerService(mAdapterService, mNativeInterface);
        // Set a mock A2dpSinkService for audio focus calls
        A2dpSinkService.setA2dpSinkService(mA2dpSinkService);

        mService.mDeviceStateMap.put(mDevice, mStateMachine);
        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);

        // Set up device and state machine under test
        mService.mDeviceStateMap.put(mDevice2, mStateMachine2);

        when(mA2dpSinkService.setActiveDevice(any(BluetoothDevice.class))).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        A2dpSinkService.setA2dpSinkService(null);
        mService = AvrcpControllerService.getAvrcpControllerService();
        assertThat(mService).isNull();
    }

    @Test
    public void initialize() {
        assertThat(AvrcpControllerService.getAvrcpControllerService()).isNotNull();
    }

    @Test
    public void disconnect_whenDisconnected_returnsFalse() {
        when(mStateMachine.getState()).thenReturn(STATE_DISCONNECTED);

        assertThat(mService.disconnect(mDevice)).isFalse();
    }

    @Test
    public void disconnect_whenDisconnected_returnsTrue() {
        when(mStateMachine.getState()).thenReturn(STATE_CONNECTED);

        assertThat(mService.disconnect(mDevice)).isTrue();
        verify(mStateMachine).disconnect();
    }

    @Test
    public void removeStateMachine() {
        when(mStateMachine.getDevice()).thenReturn(mDevice);

        mService.removeStateMachine(mStateMachine);

        assertThat(mService.mDeviceStateMap).doesNotContainKey(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        when(mAdapterService.getBondedDevices()).thenReturn(new BluetoothDevice[] {mDevice});
        when(mStateMachine.getState()).thenReturn(STATE_CONNECTED);

        assertThat(mService.getConnectedDevices()).contains(mDevice);
    }

    @Test
    public void setActiveDevice_whenA2dpSinkServiceIsNotInitialized_returnsFalse() {
        A2dpSinkService.setA2dpSinkService(null);
        assertThat(mService.setActiveDevice(mDevice)).isFalse();

        assertThat(mService.getActiveDevice()).isNull();
    }

    @Test
    public void getCurrentMetadataIfNoCoverArt_doesNotCrash() {
        mService.getCurrentMetadataIfNoCoverArt(mDevice);
    }

    @Test
    public void refreshContents() {
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(node.getDevice()).thenReturn(mDevice);

        mService.refreshContents(node);

        verify(mStateMachine).requestContents(node);
    }

    @Test
    public void playItem() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);

        mService.playItem(parentMediaId);

        verify(mStateMachine).playItem(node);
    }

    @Test
    public void getContents() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);

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
        when(mStateMachine.findNode(parentMediaId)).thenReturn(null);
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
        mService.getBrowseTree().onConnected(mDevice);
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
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);
        when(node.isCached()).thenReturn(false);
        when(node.getDevice()).thenReturn(mDevice);
        when(node.getID()).thenReturn(parentMediaId);

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
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);
        when(node.getContents()).thenReturn(new ArrayList(0));
        when(node.isCached()).thenReturn(true);

        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.status()).isEqualTo(BrowseResult.SUCCESS);
    }

    @Test
    public void handleChangeFolderRsp() {
        int count = 1;

        mService.handleChangeFolderRsp(mDevice, count);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_FOLDER_PATH, count);
    }

    @Test
    public void handleSetBrowsedPlayerRsp() {
        int items = 3;
        int depth = 5;

        mService.handleSetBrowsedPlayerRsp(mDevice, items, depth);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_BROWSED_PLAYER,
                        items,
                        depth);
    }

    @Test
    public void handleSetAddressedPlayerRsp() {
        int status = 1;

        mService.handleSetAddressedPlayerRsp(mDevice, status);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ADDRESSED_PLAYER);
    }

    @Test
    public void handleAddressedPlayerChanged() {
        int id = 1;

        mService.handleAddressedPlayerChanged(mDevice, id);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, id);
    }

    @Test
    public void handleNowPlayingContentChanged() {
        mService.handleNowPlayingContentChanged(mDevice);

        verify(mStateMachine).nowPlayingContentChanged();
    }

    @Test
    public void onConnectionStateChanged_connectCase() {
        boolean remoteControlConnected = true;
        boolean browsingConnected = true; // Calls connect when any of them is true.

        mService.onConnectionStateChanged(remoteControlConnected, browsingConnected, mDevice);

        ArgumentCaptor<StackEvent> captor = ArgumentCaptor.forClass(StackEvent.class);
        verify(mStateMachine).connect(captor.capture());
        StackEvent event = captor.getValue();
        assertThat(event.mType).isEqualTo(StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        assertThat(event.mRemoteControlConnected).isEqualTo(remoteControlConnected);
        assertThat(event.mBrowsingConnected).isEqualTo(browsingConnected);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
    }

    @Test
    public void onConnectionStateChanged_disconnectCase() {
        boolean remoteControlConnected = false;
        boolean browsingConnected = false; // Calls disconnect when both of them are false.

        mService.onConnectionStateChanged(remoteControlConnected, browsingConnected, mDevice);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
        verify(mStateMachine).disconnect();
    }

    @Test
    public void getRcPsm() {
        int psm = 1;

        mService.getRcPsm(mDevice, psm);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM, psm);
    }

    @Test
    public void handleRegisterNotificationAbsVol() {
        byte label = 1;

        mService.handleRegisterNotificationAbsVol(mDevice, label);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION,
                        label);
    }

    @Test
    public void handleSetAbsVolume() {
        byte absVol = 15;
        byte label = 1;

        mService.handleSetAbsVolume(mDevice, absVol, label);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol, label);
    }

    @Test
    public void onTrackChanged() {
        byte numAttrs = 0;
        int[] attrs = new int[0];
        String[] attrVals = new String[0];

        mService.onTrackChanged(mDevice, numAttrs, attrs, attrVals);

        ArgumentCaptor<AvrcpItem> captor = ArgumentCaptor.forClass(AvrcpItem.class);
        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_TRACK_CHANGED),
                        captor.capture());
        AvrcpItem item = captor.getValue();
        assertThat(item.getDevice()).isEqualTo(mDevice);
        assertThat(item.getItemType()).isEqualTo(AvrcpItem.TYPE_MEDIA);
        assertThat(item.getUuid()).isNotNull(); // Random uuid
    }

    @Test
    public void onPlayPositionChanged() {
        int songLen = 100;
        int currSongPos = 33;

        mService.onPlayPositionChanged(mDevice, songLen, currSongPos);

        verify(mStateMachine)
                .sendMessage(
                        AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_POS_CHANGED,
                        songLen,
                        currSongPos);
    }

    @Test
    public void onPlayStatusChanged() {
        byte status = PlaybackStateCompat.STATE_REWINDING;

        mService.onPlayStatusChanged(mDevice, status);

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

        mService.onPlayerAppSettingChanged(mDevice, playerAttribRsp, 2);

        verify(mStateMachine)
                .sendMessage(
                        eq(
                                AvrcpControllerStateMachine
                                        .MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS),
                        any(PlayerApplicationSettings.class));
    }

    @Test
    public void onAvailablePlayerChanged() {
        mService.onAvailablePlayerChanged(mDevice);

        verify(mStateMachine)
                .sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);
    }

    @Test
    public void handleGetFolderItemsRsp() {
        int status = 2;
        AvrcpItem[] items = new AvrcpItem[] {mock(AvrcpItem.class)};

        mService.handleGetFolderItemsRsp(mDevice, status, items);

        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS),
                        eq(new ArrayList<>(Arrays.asList(items))));
    }

    @Test
    public void handleGetPlayerItemsRsp() {
        List<AvrcpPlayer> items = List.of(mock(AvrcpPlayer.class));

        mService.handleGetPlayerItemsRsp(mDevice, items);

        verify(mStateMachine)
                .sendMessage(
                        eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS),
                        eq(items));
    }

    @Test
    public void dump_doesNotCrash() {
        mService.getRcPsm(mDevice, 1);
        mService.dump(new StringBuilder());
    }

    @Test
    public void testOnFocusChange_audioGainDeviceActive_sessionActivated() {
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN);
        assertThat(BluetoothMediaBrowserService.isActive()).isTrue();
    }

    @Test
    public void testOnFocusChange_audioLoss_sessionDeactivated() {
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
    }

    /**
     * Connect first device and check that it is the active device. Pair a second device, then
     * disconnect and repair this known second device. Confirm that audio focus is maintained by
     * first device by checking that it has remained as the active device.
     */
    @Test
    public void testActiveDeviceMaintainsAudioFocusWhenOtherDeviceConnects_audioFocusMaintained() {
        mService.onConnectionStateChanged(true, true, mDevice);
        // check set active device is called
        verify(mA2dpSinkService).setActiveDevice(mDevice);
        when(mA2dpSinkService.getActiveDevice()).thenReturn(mDevice);

        // connect another phone
        mService.onConnectionStateChanged(true, true, mDevice2);
        verify(mA2dpSinkService, times(0)).setActiveDevice(mDevice2);

        // disconnect and reconnect other phone
        mService.onConnectionStateChanged(false, false, mDevice2);
        mService.onConnectionStateChanged(true, true, mDevice2);
        verify(mA2dpSinkService, times(0)).setActiveDevice(mDevice2);
    }
}
