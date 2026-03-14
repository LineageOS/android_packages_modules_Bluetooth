/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.Util.getBytesFromAddress;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import static java.util.Collections.emptyList;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Looper;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.media_audio.sink.BluetoothMediaBrowserService;
import com.android.bluetooth.media_audio.sink.MediaAudioServer;
import com.android.bluetooth.media_audio.sink.MediaSource;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test cases for {@link AvrcpControllerStateMachine}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerStateMachineTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Mock private AdapterService mAdapterService;
    @Mock private MediaAudioServer mMediaAudioServer;
    @Mock private MediaSource.Callback mMediaSourceCallbacks;
    @Mock private A2dpSinkService mA2dpSinkService;
    @Mock private Resources mMockResources;
    @Mock private AvrcpControllerService mAvrcpControllerService;
    @Mock private AvrcpControllerNativeInterface mNativeInterface;
    @Mock private AvrcpCoverArtManager mCoverArtManager;
    @Mock private PlayerApplicationSettings mPlayerApplicationSettings;
    @Mock private AudioManager mAudioManager;
    @Mock private PackageManager mPackageManager;

    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 100;
    private static final int KEY_DOWN = 0;
    private static final int KEY_UP = 1;
    private static final int UUID_START = 0;
    private static final int UUID_LENGTH = 25;

    private final BluetoothDevice mDevice = getTestDevice(43);
    private final byte[] mTestAddress = getBytesFromAddress(mDevice.getAddress());

    private BrowseTree mBrowseTree;
    private BroadcastReceiver mBroadcastReceiver;
    private MediaSource mMediaSource;
    private AvrcpControllerStateMachine mAvrcpStateMachine;

    @Before
    public void setUp() throws Exception {
        mBrowseTree = new BrowseTree(mAdapterService, null);

        doReturn(STATE_DISCONNECTED).when(mCoverArtManager).getState(any());

        doReturn(15).when(mAudioManager).getStreamMaxVolume(anyInt());
        doReturn(8).when(mAudioManager).getStreamVolume(anyInt());
        doReturn(true).when(mAudioManager).isVolumeFixed();

        // Loud absolute volume (Utils.isAutomotive())
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);

        doReturn(true)
                .when(mMockResources)
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);

        doReturn(mMockResources).when(mAvrcpControllerService).getResources();
        doReturn(mBrowseTree).when(mAvrcpControllerService).getBrowseTree();

        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        doReturn(mCoverArtManager).when(mAvrcpControllerService).getCoverArtManager();
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        doReturn(Optional.of(mA2dpSinkService)).when(mAdapterService).getA2dpSinkService();

        // Start the Bluetooth Media Browser Service
        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);

        // Ensure our MediaBrowserService starts with a blank state
        BluetoothMediaBrowserService.reset();

        mAvrcpStateMachine = makeStateMachine(mDevice);

        setActiveDevice(mDevice);
    }

    @After
    public void tearDown() throws Exception {
        destroyStateMachine(mAvrcpStateMachine);
    }

    /** Create a state machine to test */
    private AvrcpControllerStateMachine makeStateMachine(BluetoothDevice device) {
        AvrcpControllerStateMachine sm =
                new AvrcpControllerStateMachine(
                        mAdapterService,
                        mAvrcpControllerService,
                        mMediaAudioServer,
                        device,
                        mNativeInterface);
        sm.start();
        return sm;
    }

    /** Destroy a state machine you created to test */
    private void destroyStateMachine(AvrcpControllerStateMachine sm) {
        if (sm == null || sm.getState() == STATE_DISCONNECTED) return;

        sm.disconnect();
        TestUtils.waitForLooperToBeIdle(sm.getHandler().getLooper());

        // is disconnected
        assertThat(sm.getState()).isEqualTo(STATE_DISCONNECTED);

        // told mAvrcpControllerService to remove it
        verify(mAvrcpControllerService).removeStateMachine(eq(sm));
    }

    private void verifyAndCaptureMediaSourceRegistration() {
        // TODO(Flag.media_audio_server): Upgrade this to an assert once flag is removed
        var captor = ArgumentCaptor.forClass(MediaSource.class);
        verify(mMediaAudioServer, atLeast(0)).registerMediaSource(captor.capture());
        if (!captor.getAllValues().isEmpty()) {
            mMediaSource = captor.getValue();
            assertThat(mMediaSource).isNotNull();
            mMediaSource.registerCallback(mMediaSourceCallbacks);
        }
    }

    /** Set up which device the AvrcpControllerService will report as active */
    private void setActiveDevice(BluetoothDevice device) {
        doReturn(device).when(mAvrcpControllerService).getActiveDevice();
        if (mDevice.equals(device)) {
            mAvrcpStateMachine.setDeviceState(AvrcpControllerService.DEVICE_STATE_ACTIVE);
        } else {
            mAvrcpStateMachine.setDeviceState(AvrcpControllerService.DEVICE_STATE_INACTIVE);
            BluetoothMediaBrowserService.reset();
        }
    }

    /** Send an audio focus changed event to the state machine under test */
    private void sendAudioFocusUpdate(int state) {
        doReturn(state).when(mA2dpSinkService).getFocusState();
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.AUDIO_FOCUS_STATE_CHANGE, state);
    }

    /** Setup Connected State for a given state machine */
    private void setUpConnectedState(boolean control, boolean browsing) {
        assertThat(mAvrcpStateMachine.getCurrentState())
                .isInstanceOf(AvrcpControllerStateMachine.Disconnected.class);

        mAvrcpStateMachine.connect(control, browsing);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.getCurrentState())
                .isInstanceOf(AvrcpControllerStateMachine.Connected.class);
        assertThat(mAvrcpStateMachine.getState()).isEqualTo(STATE_CONNECTED);

        // Capture broadcast receiver
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        // The temporary workaround below is done because the receiver is only registered when
        // Flags.avrcpControllerAbsVolChangedNotification() is true
        verify(mAdapterService, atLeast(0))
                .registerReceiver(receiverCaptor.capture(), any(IntentFilter.class), any(), any());
        List<BroadcastReceiver> receivers = receiverCaptor.getAllValues();
        mBroadcastReceiver = !receivers.isEmpty() ? receivers.getLast() : null;
        // Use this when removing flag
        // verify(mAdapterService, atLeastOnce())
        //     .registerReceiver(receiverCaptor.capture(), any(IntentFilter.class));
        // mBroadcastReceiver = receiverCaptor.getValue();
        verifyAndCaptureMediaSourceRegistration();
    }

    private AvrcpItem makeTrack(
            String title,
            String artist,
            String album,
            long trackNum,
            long totalTracks,
            String genre,
            long duration,
            String imageHandle) {
        return makeTrack(
                "AVRCP-ITEM-TEST-UUID",
                0,
                title,
                artist,
                album,
                trackNum,
                totalTracks,
                genre,
                duration,
                imageHandle);
    }

    private AvrcpItem makeTrack(
            String uuid,
            int uid,
            String title,
            String artist,
            String album,
            long trackNum,
            long totalTracks,
            String genre,
            long duration,
            String imageHandle) {
        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setItemType(AvrcpItem.TYPE_MEDIA);
        builder.setType(AvrcpItem.MEDIA_AUDIO);
        builder.setDevice(mDevice);
        builder.setPlayable(true);
        builder.setUid(uid);
        builder.setUuid(uuid);

        builder.setTitle(title);
        builder.setArtistName(artist);
        builder.setAlbumName(album);
        builder.setTrackNumber(trackNum);
        builder.setTotalNumberOfTracks(totalTracks);
        builder.setGenre(genre);
        builder.setPlayingTime(duration);
        if (imageHandle != null) {
            builder.setCoverArtHandle(imageHandle);
        }

        return builder.build();
    }

    private static AvrcpPlayer makePlayer(
            BluetoothDevice device,
            int playerId,
            String playerName,
            byte[] playerFeatures,
            int playStatus) {
        AvrcpPlayer.Builder apb = new AvrcpPlayer.Builder();
        apb.setDevice(device);
        apb.setPlayerId(playerId);
        apb.setName(playerName);
        apb.setSupportedFeatures(playerFeatures);
        apb.setPlayStatus(playStatus);
        return apb.build();
    }

    /**
     * Send a message to the state machine that the track has changed. Must be connected to do this.
     */
    private void setCurrentTrack(AvrcpItem track) {
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_TRACK_CHANGED, track);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.getCurrentTrack()).isEqualTo(track);
    }

    /** Set the current play status (Play, Pause, etc.) of the device */
    private void setPlaybackState(int state) {
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED, state);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
    }

    /** Set the current playback position of the device */
    private void setPlaybackPosition(int position, int duration) {
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_POS_CHANGED, duration, position);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
    }

    /** Make an AvrcpItem suitable for being included in the Now Playing list for the test device */
    private AvrcpItem makeNowPlayingItem(long uid, String name) {
        AvrcpItem.Builder aib = new AvrcpItem.Builder();
        aib.setDevice(mDevice);
        aib.setItemType(AvrcpItem.TYPE_MEDIA);
        aib.setType(AvrcpItem.MEDIA_AUDIO);
        aib.setTitle(name);
        aib.setUid(uid);
        aib.setUuid(UUID.randomUUID().toString());
        aib.setPlayable(true);
        return aib.build();
    }

    /** Get the current Now Playing list for the test device */
    private List<AvrcpItem> getNowPlayingList() {
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        for (BrowseTree.BrowseNode child : nowPlaying.getChildren()) {
            nowPlayingList.add(child.mItem);
        }
        return nowPlayingList;
    }

    /** Set the current Now Playing list for the test device */
    private void setNowPlayingList(List<AvrcpItem> nowPlayingList) {
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS, nowPlayingList);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        // Wait for the now playing list to be propagated
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure its set by re grabbing the node and checking its contents are cached
        nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        assertThat(nowPlaying.isCached()).isTrue();
        assertThat(getNowPlayingList()).containsExactlyElementsIn(nowPlayingList).inOrder();
    }

    // Absolute Volume Test Utilities

    /**
     * Create a state machine for absolute volume tests after configuring fixed volume and
     * automotive settings (affects volume strategy)
     */
    private void makeStateMachineForAbsVolumeTests(boolean isVolumeFixed, boolean isAutomotive) {
        destroyStateMachine(mAvrcpStateMachine);

        doReturn(isVolumeFixed).when(mAudioManager).isVolumeFixed();
        // Loud absolute volume (Utils.isAutomotive())
        doReturn(isAutomotive)
                .when(mPackageManager)
                .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);

        doReturn(100).when(mAudioManager).getStreamMaxVolume(eq(AudioManager.STREAM_MUSIC));
        doReturn(25).when(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC));

        mAvrcpStateMachine = makeStateMachine(mDevice);
    }

    /**
     * Send a register absolute volume notification command to the state machine and drive its
     * looper.
     */
    private void registerAbsoluteVolumeNotification(byte label) {
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION, label);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
    }

    /** Verify that an absolute volume interim response was sent to the native interface. */
    private void verifyAbsoluteVolumeInterimResponse(byte label, int absVolRsp) {
        verify(mNativeInterface)
                .sendRegisterAbsVolInterimRsp(any(), eq(absVolRsp), eq((int) label));
    }

    /** Send a set absolute volume command to the state machine and drive its looper. */
    private void setAbsoluteVolume(byte setLabel, int absVol) {
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol, setLabel);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
    }

    /** Verify that a set absolute volume response was sent to the native interface. */
    private void verifySetAbsoluteVolumeResponse(byte setLabel, int absVolRsp) {
        verify(mNativeInterface).sendSetAbsVolRsp(any(), eq(absVolRsp), eq((int) setLabel));
    }

    /** Verify that {@code AudioManager.setStreamVolume} is called with the expected value. */
    private void verifySetStreamVolume(int localVol) {
        verify(mAudioManager)
                .setStreamVolume(
                        eq(AudioManager.STREAM_MUSIC), eq(localVol), eq(AudioManager.FLAG_SHOW_UI));
        doReturn(localVol).when(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC));
    }

    /** Verify that {@code AudioManager.setStreamVolume} is not called. */
    private void verifyNoSetStreamVolume() {
        verify(mAudioManager, never())
                .setStreamVolume(
                        eq(AudioManager.STREAM_MUSIC), anyInt(), eq(AudioManager.FLAG_SHOW_UI));
    }

    /**
     * Send a volume changed event for volume level: {@code localVol}, in the device's domain, not
     * the absolute volume domain.
     *
     * <p>Only use with {@link Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION}
     */
    private void sendVolumeChangedEvent(int localVol) {
        doReturn(localVol).when(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC));
        Intent intent =
                new Intent(AudioManager.ACTION_VOLUME_CHANGED)
                        .putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                        .putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, localVol);
        mBroadcastReceiver.onReceive(mAdapterService, intent);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
    }

    /** Verify that an absolute volume changed notification was sent to the native interface. */
    private void verifyAbsoluteVolumeChangedNotification(byte label, int absVol) {
        verify(mNativeInterface).sendRegisterAbsVolChangedRsp(any(), eq(absVol), eq((int) label));
    }

    /** Verify that an absolute volume changed notification was not sent to the native interface. */
    private void verifyNoAbsoluteVolumeChangedNotification() {
        verify(mNativeInterface, never()).sendRegisterAbsVolChangedRsp(any(), anyInt(), anyInt());
    }

    private void setAvailablePlayers(List<AvrcpPlayer> playerList) {
        mAvrcpStateMachine.requestContents(mAvrcpStateMachine.mBrowseTree.mRootNode);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, times(1)).getPlayerList(eq(mTestAddress), eq(0), eq(19));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, playerList);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
    }

    private void setFolderContents(BrowseTree.BrowseNode folder, List<AvrcpItem> folderList) {
        mAvrcpStateMachine.requestContents(folder);

        int start = 0;
        int end = Math.min(20, folderList.size());
        int count = folderList.size();
        while (count > 0) {
            List<AvrcpItem> results = new ArrayList<>(folderList.subList(start, end));
            mAvrcpStateMachine.sendMessage(
                    AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS, results);
            count = count - (end - start);
            start = end;
            end = Math.min(end + 20, folderList.size());
        }
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
    }

    /**
     * Test to confirm that the state machine is capable of cycling through the 4 connection states,
     * and that upon completion, it cleans up afterwards.
     */
    @Test
    public void testDisconnect() {
        setUpConnectedState(true, true);
        testDisconnectInternal();
    }

    /**
     * Test to confirm that the state machine is capable of cycling through the 4 connection states
     * with no crashes, even if the {@link AvrcpControllerService} is stopped and the {@code
     * sBrowseTree} is null. This could happen if BT is disabled as the profile is being
     * disconnected.
     */
    @Test
    public void testDisconnectWithNullBrowseTree() {
        setUpConnectedState(true, true);

        testDisconnectInternal();
    }

    private void testDisconnectInternal() {
        mAvrcpStateMachine.disconnect();
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.getCurrentState())
                .isInstanceOf(AvrcpControllerStateMachine.Disconnected.class);
        assertThat(mAvrcpStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
    }

    /** Test to confirm that a control only device can be established (no browsing) */
    @Test
    public void testControlOnly() {
        setUpConnectedState(true, false);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        assertThat(transportControls).isNotNull();

        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();
        assertThat(mediaBrowserService.getPlaybackState().getState())
                .isEqualTo(PlaybackStateCompat.STATE_NONE);

        mAvrcpStateMachine.disconnect();
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.getCurrentState())
                .isInstanceOf(AvrcpControllerStateMachine.Disconnected.class);
        assertThat(mAvrcpStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
    }

    /** Test to confirm that a browsing only device can be established (no control) */
    @Test
    @FlakyTest
    public void testBrowsingOnly() {
        assertThat(mBrowseTree.mRootNode.getChildrenCount()).isEqualTo(0);
        setUpConnectedState(false, true);
        assertThat(mBrowseTree.mRootNode.getChildrenCount()).isEqualTo(1);

        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();
        assertThat(mediaBrowserService.getPlaybackState().getState())
                .isEqualTo(PlaybackStateCompat.STATE_NONE);

        mAvrcpStateMachine.disconnect();
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.getCurrentState())
                .isInstanceOf(AvrcpControllerStateMachine.Disconnected.class);
        assertThat(mAvrcpStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
    }

    /** Get the root of the device */
    @Test
    public void testGetDeviceRootNode_rootNodeMatchesUuidFormat() {
        setUpConnectedState(true, true);
        final String rootName = "__ROOT__" + mDevice.getAddress().toString();
        // Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;
        assertThat((results.getID()).substring(UUID_START, UUID_LENGTH)).isEqualTo(rootName);
    }

    /** Test to make sure the state machine is tracking the correct device */
    @Test
    public void testGetDevice() {
        assertThat(mAvrcpStateMachine.getDevice()).isEqualTo(mDevice);
    }

    /** Test that dumpsys will generate information when cover art is disconnected */
    @Test
    public void testDump_coverArtDisconnected() {
        StringBuilder sb = new StringBuilder();
        mAvrcpStateMachine.dump(sb);
        assertThat(sb.toString()).contains("Cover Art: false");
    }

    /** Test that dumpsys will generate information when cover art is connected */
    @Test
    public void testDump_coverArtConnected() {
        doReturn(STATE_CONNECTED).when(mCoverArtManager).getState(mDevice);
        StringBuilder sb = new StringBuilder();
        mAvrcpStateMachine.dump(sb);
        assertThat(sb.toString()).contains("Cover Art: true");
    }

    /** Test that dumpsys will generate information when cover art manager is null */
    @Test
    public void testDump_coverArtManagerNull() {
        // Override the setup to return a null cover art manager
        doReturn(null).when(mAvrcpControllerService).getCoverArtManager();
        // Create a new state machine with this setup
        AvrcpControllerStateMachine smWithNullManager = makeStateMachine(mDevice);

        StringBuilder sb = new StringBuilder();
        smWithNullManager.dump(sb);
        assertThat(sb.toString()).contains("Cover Art: false, mCoverArtManager is null");

        // Clean up the new state machine
        destroyStateMachine(smWithNullManager);
    }

    /** Test media browser play command */
    @Test
    public void testPlay() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Play
        transportControls.play();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /** Test media browser pause command */
    @Test
    public void testPause() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Pause
        transportControls.pause();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /** Test media browser stop command */
    @Test
    public void testStop() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Stop
        transportControls.stop();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_UP));
    }

    /** Test media browser next command */
    @Test
    public void testNext() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Next
        transportControls.skipToNext();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD),
                        eq(KEY_UP));
    }

    /** Test media browser previous command */
    @Test
    public void testPrevious() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Previous
        transportControls.skipToPrevious();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD),
                        eq(KEY_UP));
    }

    /** Test media browser fast forward command */
    @Test
    @FlakyTest
    public void testFastForward() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // FastForward
        transportControls.fastForward();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FF),
                        eq(KEY_DOWN));
        // Finish FastForwarding
        transportControls.play();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FF),
                        eq(KEY_UP));
    }

    /** Test media browser rewind command */
    @Test
    public void testRewind() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Rewind
        transportControls.rewind();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_REWIND),
                        eq(KEY_DOWN));
        // Finish Rewinding
        transportControls.play();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_REWIND),
                        eq(KEY_UP));
    }

    /** Test media browser skip to queue item */
    @Test
    public void testSkipToQueueInvalid() throws Exception {
        byte scope = 1;
        int minSize = 0;
        int maxSize = 255;
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Play an invalid item below start
        transportControls.skipToQueueItem(minSize - 1);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0))
                .playItem(eq(mTestAddress), eq(scope), anyLong(), anyInt());

        // Play an invalid item beyond end
        transportControls.skipToQueueItem(maxSize + 1);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0))
                .playItem(eq(mTestAddress), eq(scope), anyLong(), anyInt());
    }

    /** Test media browser shuffle command */
    @Test
    public void testShuffle() throws Exception {
        byte[] shuffleSetting = new byte[] {3};
        byte[] shuffleMode = new byte[] {2};

        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Shuffle
        transportControls.setShuffleMode(1);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setPlayerApplicationSettingValues(
                        eq(mTestAddress), eq((byte) 1), eq(shuffleSetting), eq(shuffleMode));
    }

    /** Test media browser repeat command */
    @Test
    public void testRepeat() throws Exception {
        byte[] repeatSetting = new byte[] {2};
        byte[] repeatMode = new byte[] {3};

        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        // Shuffle
        transportControls.setRepeatMode(2);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setPlayerApplicationSettingValues(
                        eq(mTestAddress), eq((byte) 1), eq(repeatSetting), eq(repeatMode));
    }

    /**
     * Test media browsing Verify that a browse tree is created with the proper root Verify that a
     * player can be fetched and added to the browse tree Verify that the contents of a player are
     * fetched upon request
     */
    @Test
    @FlakyTest
    public void testBrowsingCommands() {
        setUpConnectedState(true, true);
        final String playerName = "Player 1";
        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        mAvrcpStateMachine.requestContents(results);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));

        // Provide back a player object
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, playerName, playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify that the player object is available.
        assertThat(results.isCached()).isTrue();
        assertThat(results.getChildren().get(0).getMediaItem().toString())
                .isEqualTo("MediaItem{mFlags=1, mDescription=" + playerName + ", null, null}");

        // Fetch contents of that player object
        BrowseTree.BrowseNode playerOneNode =
                mAvrcpStateMachine.findNode(results.getChildren().get(0).getID());
        mAvrcpStateMachine.requestContents(playerOneNode);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setBrowsedPlayer(eq(mTestAddress), eq(1));
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_FOLDER_PATH, 5);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getFolderList(eq(mTestAddress), eq(0), eq(4));
    }

    /**
     * Test media browsing Verify that a browse tree is created with the proper root Verify that a
     * player can be fetched and added to the browse tree Verify that the contents of a player are
     * fetched upon request
     */
    @Test
    @FlakyTest
    public void testBrowsingCommandsNoBrowse() {
        setUpConnectedState(true, false);
        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Verify requestContents does not start to fetch players.
        mAvrcpStateMachine.requestContents(results);
        verify(mNativeInterface, never()).getPlayerList(eq(mTestAddress), anyInt(), anyInt());
    }

    /**
     * Test our reaction to an available players changed event
     *
     * <p>Verify that we issue a command to fetch the new available players
     */
    @Test
    public void testAvailablePlayersChanged() {
        setUpConnectedState(true, true);

        // Send an available players have changed event
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);

        // Verify we've uncached our browse root and made the call to fetch new players
        assertThat(mAvrcpStateMachine.mBrowseTree.mRootNode.isCached()).isFalse();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));
    }

    /**
     * Test how we handle receiving an available players list that contains the player we know to be
     * the addressed player
     */
    @Test
    public void testAvailablePlayersReceived_AddressedPlayerExists() {
        setUpConnectedState(true, true);

        // Set an addressed player that will be in the available players set. A new player triggers
        // a now playing list download, so send back nothing.
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 1);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Send an available players have changed event
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);

        // Verify we've uncached our browse root and made the call to fetch new players
        assertThat(mAvrcpStateMachine.mBrowseTree.mRootNode.isCached()).isFalse();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));

        // Send available players set that contains our addressed player
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "Player 1", playerFeatures, 1);
        AvrcpPlayer playerTwo = makePlayer(mDevice, 2, "Player 2", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        testPlayers.add(playerTwo);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);

        // Wait for them to be processed
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify we processed the first players properly. Note the addressed player should always
        // be in the available player set.
        assertThat(mAvrcpStateMachine.mBrowseTree.mRootNode.isCached()).isTrue();
        SparseArray<AvrcpPlayer> players = mAvrcpStateMachine.getAvailablePlayers();
        assertThat(players.contains(mAvrcpStateMachine.getAddressedPlayerId())).isTrue();
        assertThat(players.size()).isEqualTo(testPlayers.size());
        for (AvrcpPlayer player : testPlayers) {
            assertThat(players.contains(player.getId())).isTrue();
        }

        // Verify we request metadata, playback state and now playing list
        assertThat(getNowPlayingList()).isEmpty();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getCurrentMetadata(eq(mTestAddress));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlaybackState(eq(mTestAddress));
    }

    /**
     * Test how we handle receiving an available players list that does not contain the player we
     * know to be the addressed player
     */
    @Test
    public void testAvailablePlayersReceived_AddressedPlayerDoesNotExist() {
        setUpConnectedState(true, true);

        // Send an available players have changed event
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);

        // Verify we've uncached our browse root and made the call to fetch new players
        assertThat(mAvrcpStateMachine.mBrowseTree.mRootNode.isCached()).isFalse();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));

        // Send available players set that does not contain the addressed player
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "Player 1", playerFeatures, 1);
        AvrcpPlayer playerTwo = makePlayer(mDevice, 2, "Player 2", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        testPlayers.add(playerTwo);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);

        // Wait for them to be processed
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify we processed the players properly. Note the addressed player is currently the
        // default player and is not in the available player set sent. This means we'll have an
        // extra player at ID -1.
        assertThat(mAvrcpStateMachine.mBrowseTree.mRootNode.isCached()).isTrue();
        SparseArray<AvrcpPlayer> players = mAvrcpStateMachine.getAvailablePlayers();
        assertThat(players.contains(mAvrcpStateMachine.getAddressedPlayerId())).isTrue();
        assertThat(players.size()).isEqualTo(testPlayers.size() + 1);
        for (AvrcpPlayer player : testPlayers) {
            assertThat(players.contains(player.getId())).isTrue();
        }

        // Verify we do not request metadata, playback state and now playing list because we're
        // sure the addressed player and metadata we have isn't impacted by the new players
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0))
                .getNowPlayingList(any(), anyInt(), anyInt());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0))
                .getCurrentMetadata(any());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0))
                .getPlaybackState(any());
    }

    /**
     * Test addressed media player changing to a player we know about Verify when the addressed
     * media player changes browsing data updates
     */
    @Test
    public void testAddressedPlayerChangedToNewKnownPlayer() {
        setUpConnectedState(true, true);
        // Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        mAvrcpStateMachine.requestContents(results);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));

        // Provide back two player objects, IDs 1 and 2
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "Player 1", playerFeatures, 1);
        AvrcpPlayer playerTwo = makePlayer(mDevice, 2, "Player 2", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        testPlayers.add(playerTwo);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);

        // Set something arbitrary for the current Now Playing list
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        nowPlayingList.add(makeNowPlayingItem(1, "Song 1"));
        nowPlayingList.add(makeNowPlayingItem(2, "Song 2"));
        setNowPlayingList(nowPlayingList);
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Change players and verify that BT attempts to update the results
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 2);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // The addressed player should always be in the available player set
        assertThat(mAvrcpStateMachine.getAddressedPlayerId()).isEqualTo(2);
        SparseArray<AvrcpPlayer> players = mAvrcpStateMachine.getAvailablePlayers();
        assertThat(players.contains(mAvrcpStateMachine.getAddressedPlayerId())).isTrue();

        // Make sure the Now Playing list is now cleared
        assertThat(getNowPlayingList()).isEmpty();

        // Verify that a player change to a player with Now Playing support causes a refresh.
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        // Verify we request metadata and playback state
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getCurrentMetadata(eq(mTestAddress));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlaybackState(eq(mTestAddress));
    }

    /**
     * Test addressed media player change to a player we don't know about Verify when the addressed
     * media player changes browsing data updates Verify that the contents of a player are fetched
     * upon request
     */
    @Test
    public void testAddressedPlayerChangedToUnknownPlayer() {
        setUpConnectedState(true, true);

        // Get the root of the device
        BrowseTree.BrowseNode rootNode = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        mAvrcpStateMachine.requestContents(rootNode);

        // Provide back a player object
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "Player 1", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);

        // Set something arbitrary for the current Now Playing list
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        nowPlayingList.add(makeNowPlayingItem(1, "Song 1"));
        nowPlayingList.add(makeNowPlayingItem(2, "Song 2"));
        setNowPlayingList(nowPlayingList);

        // Change players
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 4);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure the Now Playing list is now cleared and we requested metadata
        assertThat(getNowPlayingList()).isEmpty();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getCurrentMetadata(eq(mTestAddress));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlaybackState(eq(mTestAddress));
    }

    /**
     * Test what we do when we receive an addressed player change to a player with the same ID as
     * the current addressed play.
     *
     * <p>Verify we assume nothing and re-fetch the current metadata and playback status.
     */
    @Test
    public void testAddressedPlayerChangedToSamePlayerId() {
        setUpConnectedState(true, true);

        // Set the addressed player so we can change to the same one
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 1);

        // Wait until idle so Now Playing List is queried for, resolve it
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Get the root of the device
        BrowseTree.BrowseNode rootNode = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        mAvrcpStateMachine.requestContents(rootNode);

        // Send available players set that contains our addressed player
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "Player 1", playerFeatures, 1);
        AvrcpPlayer playerTwo = makePlayer(mDevice, 2, "Player 2", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        testPlayers.add(playerTwo);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);

        // Wait until idle so Now Playing List is queried for again, resolve it again
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Send an addressed player changed to the same player ID
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 1);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        // Verify we make no assumptions about the player ID and still fetch metadata, play status
        // and now playing list (since player 1 supports it)
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getCurrentMetadata(eq(mTestAddress));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlaybackState(eq(mTestAddress));
    }

    /** Test that the Now Playing playlist is updated when it changes. */
    @Test
    public void testNowPlaying() {
        setUpConnectedState(true, true);
        mAvrcpStateMachine.nowPlayingContentChanged();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
    }

    /** Test that AVRCP events such as playback commands can execute while performing browsing. */
    @Test
    public void testPlayWhileBrowsing() {
        setUpConnectedState(true, true);

        // Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        mAvrcpStateMachine.requestContents(results);

        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        transportControls.play();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /** Test that Absolute Volume Registration is working: Strategy Loud */
    @Test
    public void testRegisterAbsVolumeNotification_isStrategyLoud_respondsAbsVolumeMax() {
        makeStateMachineForAbsVolumeTests(false, true);
        setUpConnectedState(true, true);

        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 127);
    }

    /** Test that Absolute Volume Registration is working: Strategy Absolute */
    @Test
    public void testRegisterAbsVolumeNotification_isStrategyAbsolute_doesNotRespondAbsVolumeMax() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);
    }

    /** Test that set absolute volume is working: Strategy Loud */
    @Test
    public void testSetAbsoluteVolume_isStrategyLoud_respondsAbsVolumeMax() {
        makeStateMachineForAbsVolumeTests(false, true);
        setUpConnectedState(true, true);

        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 127);
        // Loud devices should never set stream volume
        verifyNoSetStreamVolume();
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /** Test that set absolute volume is working: Strategy Absolute */
    @Test
    public void testSetAbsoluteVolume_isStrategyAbsolute_doesNotRespondAbsVolumeMax() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 20);
        verifySetStreamVolume(16);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /** Test that set absolute volume is working: Strategy Absolute */
    @Test
    public void testSetAbsoluteVolume_isStrategyAbsolute_currentVol_doesNotSetStreamVolume() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 32);
        verifySetAbsoluteVolumeResponse(setLabel, 32);
        // Setting absolute volume to match the current stream volume shouldn't change the stream
        // volume
        // Absolute volume 32 -> Local volume 25 == current stream volume
        verifyNoSetStreamVolume();
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /** Loud devices should not send a notification after a volume changed event */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyLoud_notificationNotSent() {
        makeStateMachineForAbsVolumeTests(false, true);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 127);

        // Volume changed event
        sendVolumeChangedEvent(39);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /**
     * If the remote device has registered for a Volume Changed Notification, absolute volume
     * devices should send a notification after a volume changed event.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_notificationSent() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Volume changed event
        sendVolumeChangedEvent(16);
        verifyAbsoluteVolumeChangedNotification(label, 20);
    }

    /**
     * If the remote device has not registered for a Volume Changed Notification, absolute volume
     * devices should not send a notification after a volume changed event.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_noRegistration_notificationNotSent() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Volume changed event when not registered
        sendVolumeChangedEvent(16);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /**
     * If a volume changed event matches the current stream volume, absolute volume devices should
     * not send a notification.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_currentVol_notificationNotSent() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Volume changed event that matches the current stream volume
        // Current stream volume: 25
        sendVolumeChangedEvent(25);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /**
     * If a volume changed event occurs after setting absolute volume, for a different volume than
     * was set, absolute volume devices should send a notification.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_afterSetAbsVol_differentVol_notificationSent() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register for first notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Set absolute volume
        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 20);
        verifySetStreamVolume(16);
        verifyNoAbsoluteVolumeChangedNotification();

        // Volume changed event for a different volume than was set
        sendVolumeChangedEvent(39);
        verifyAbsoluteVolumeChangedNotification(label, 50);
    }

    /**
     * If a volume changed event occurs after setting absolute volume, for the same volume that was
     * set, absolute volume devices should not send a notification.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_afterSetAbsVol_sameVol_notificationNotSent() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Set absolute volume
        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 20);
        verifySetStreamVolume(16);
        verifyNoAbsoluteVolumeChangedNotification();

        // Volume changed event for the same volume that was set
        sendVolumeChangedEvent(16);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /**
     * When setting absolute volume after a volume changed event occurs, to a different volume than
     * the event, absolute volume devices should not send a notification.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_isStrategyAbsolute_beforeSetAbsVol_differentVol_setsStreamVolume() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Volume changed event
        sendVolumeChangedEvent(39);
        verifyAbsoluteVolumeChangedNotification(label, 50);

        // Register notification
        label++;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 50);

        clearInvocations(mNativeInterface);

        // Set absolute volume to a different volume than the event
        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 20);
        verifySetStreamVolume(16);
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /**
     * When setting absolute volume after a volume changed event occurs, to the same volume as the
     * event, absolute volume devices should not send a notification.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    public void testEvent_beforeSetAbsVol_sameVol_isStrategyAbsolute_doesNotSetStreamVolume() {
        makeStateMachineForAbsVolumeTests(false, false);
        setUpConnectedState(true, true);

        // Register notification
        byte label = 42;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 32);

        // Volume changed event
        sendVolumeChangedEvent(16);
        verifyAbsoluteVolumeChangedNotification(label, 20);

        // Register notification
        label++;
        registerAbsoluteVolumeNotification(label);
        verifyAbsoluteVolumeInterimResponse(label, 20);

        clearInvocations(mNativeInterface);

        // Set absolute volume to the same volume as the event
        byte setLabel = 52;
        setAbsoluteVolume(setLabel, 20);
        verifySetAbsoluteVolumeResponse(setLabel, 20);
        // Setting absolute volume with the same volume as the previous event shouldn't change the
        // stream volume
        verifyNoSetStreamVolume();
        verifyNoAbsoluteVolumeChangedNotification();
    }

    /** Test playback does not request focus when another app is playing music. */
    @Test
    public void testPlaybackWhileMusicPlaying() {
        doReturn(false)
                .when(mMockResources)
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
        doReturn(AudioManager.AUDIOFOCUS_NONE).when(mA2dpSinkService).getFocusState();
        doReturn(true).when(mAudioManager).isMusicActive();
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mA2dpSinkService, never()).requestAudioFocus(mDevice, true);
    }

    /** Test playback requests focus while nothing is playing music. */
    @Test
    public void testPlaybackWhileIdle() {
        doReturn(AudioManager.AUDIOFOCUS_NONE).when(mA2dpSinkService).getFocusState();
        doReturn(false).when(mAudioManager).isMusicActive();
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mA2dpSinkService).requestAudioFocus(mDevice, true);
    }

    /**
     * Test receiving a playback status of playing while we're in an error state as it relates to
     * getting audio focus.
     *
     * <p>Verify we send a pause command and never attempt to request audio focus
     */
    @Test
    public void testPlaybackWhileErrorState() {
        doReturn(AudioManager.ERROR).when(mA2dpSinkService).getFocusState();
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mA2dpSinkService, never()).requestAudioFocus(mDevice, true);
    }

    /**
     * Test receiving a playback status of playing while we have focus
     *
     * <p>Verify we do not send a pause command and never attempt to request audio focus
     */
    @Test
    public void testPlaybackWhilePlayingState() {
        doReturn(AudioManager.AUDIOFOCUS_GAIN).when(mA2dpSinkService).getFocusState();
        setUpConnectedState(true, true);
        assertThat(mAvrcpStateMachine.isActive()).isTrue();
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mA2dpSinkService, never()).requestAudioFocus(mDevice, true);
    }

    /** Test that isActive() reports the proper value when we're active */
    @Test
    public void testIsActive_deviceActive() {
        assertThat(mAvrcpStateMachine.isActive()).isTrue();
    }

    /** Test that isActive() reports the proper value when we're inactive */
    @Test
    public void testIsActive_deviceInactive() {
        setActiveDevice(null);
        assertThat(mAvrcpStateMachine.isActive()).isFalse();
    }

    /** Test becoming active from the inactive state */
    @Test
    public void testBecomeActive() {
        // Note device starts as active in setUp() and state cascades come the CONNECTED state
        setUpConnectedState(true, true);
        assertThat(mAvrcpStateMachine.isActive()).isTrue();

        // Make the device inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isFalse();

        // Change device state while inactive
        AvrcpItem track = makeTrack("title", "artist", "album", 1, 10, "none", 10, null);
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        AvrcpItem queueItem1 = makeNowPlayingItem(0, "title");
        AvrcpItem queueItem2 = makeNowPlayingItem(1, "title 2");
        nowPlayingList.add(queueItem1);
        nowPlayingList.add(queueItem2);
        setCurrentTrack(track);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        setPlaybackPosition(7, 10);
        setNowPlayingList(nowPlayingList);

        // Make device active
        setActiveDevice(mDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isTrue();

        // See that state from BluetoothMediaBrowserService is updated
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        MediaMetadataCompat metadata = mediaBrowserService.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)).isEqualTo("title");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).isEqualTo("artist");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).isEqualTo("album");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER)).isEqualTo(1);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS)).isEqualTo(10);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)).isEqualTo("none");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)).isEqualTo(10);

        PlaybackStateCompat playbackState = mediaBrowserService.getPlaybackState();
        assertThat(playbackState).isNotNull();
        assertThat(playbackState.getState()).isEqualTo(PlaybackStateCompat.STATE_PAUSED);
        assertThat(playbackState.getPosition()).isEqualTo(7);

        List<MediaSessionCompat.QueueItem> queue = mediaBrowserService.getNowPlayingQueue();
        assertThat(queue).isNotNull();
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getDescription().getTitle().toString()).isEqualTo("title");
        assertThat(queue.get(1).getDescription().getTitle().toString()).isEqualTo("title 2");
    }

    /** Test becoming inactive from the active state */
    @Test
    public void testBecomeInactive() {
        // Note device starts as active in setUp()
        setUpConnectedState(true, true);
        assertThat(mAvrcpStateMachine.isActive()).isTrue();

        // Set the active device to something else, verify we're inactive and send a pause upon
        // becoming inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        assertThat(mAvrcpStateMachine.isActive()).isFalse();
    }

    @Test
    public void testTrackChangedWhileActive_currentTrackAndQueueNumberUpdated() {
        setUpConnectedState(true, true);

        // Set track
        AvrcpItem track = makeTrack("Song 1", "artist", "album", 1, 2, "none", 10, null);
        setCurrentTrack(track);

        // Set current Now Playing list
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        nowPlayingList.add(makeNowPlayingItem(1, "Song 1"));
        nowPlayingList.add(makeNowPlayingItem(2, "Song 2"));
        setNowPlayingList(nowPlayingList);

        // Set playing
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);

        // Wait
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify track and playback state
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        MediaMetadataCompat metadata = mediaBrowserService.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)).isEqualTo("Song 1");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).isEqualTo("artist");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).isEqualTo("album");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER)).isEqualTo(1);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS)).isEqualTo(2);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)).isEqualTo("none");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)).isEqualTo(10);

        PlaybackStateCompat playbackState = mediaBrowserService.getPlaybackState();
        assertThat(playbackState).isNotNull();
        assertThat(playbackState.getState()).isEqualTo(PlaybackStateCompat.STATE_PLAYING);
        assertThat(playbackState.getActiveQueueItemId()).isEqualTo(0);

        // Track changes, with new metadata and new track number
        track = makeTrack("Song 2", "artist", "album", 2, 2, "none", 10, null);
        setCurrentTrack(track);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Assert new track metadata and active queue item
        metadata = mediaBrowserService.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)).isEqualTo("Song 2");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).isEqualTo("artist");
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).isEqualTo("album");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER)).isEqualTo(2);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS)).isEqualTo(2);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)).isEqualTo("none");
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)).isEqualTo(10);

        playbackState = mediaBrowserService.getPlaybackState();
        assertThat(playbackState).isNotNull();
        assertThat(playbackState.getState()).isEqualTo(PlaybackStateCompat.STATE_PLAYING);
        assertThat(playbackState.getActiveQueueItemId()).isEqualTo(1);
    }

    /** Test receiving a track change update when we're not the active device */
    @Test
    public void testTrackChangeWhileNotActiveDevice() {
        setUpConnectedState(true, true);

        // Set the active device to something else, verify we're inactive and send a pause upon
        // becoming inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isFalse();

        // Change track while inactive
        AvrcpItem track = makeTrack("title", "artist", "album", 1, 10, "none", 10, null);
        setCurrentTrack(track);

        // Since we're not active, verify BluetoothMediaBrowserService does not have these values
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        // track starts as null and shouldn't change
        assertThat(mediaBrowserService.getMetadata()).isNull();
    }

    /** Test receiving a playback status of playing when we're not the active device */
    @Test
    public void testPlaybackWhileNotActiveDevice() {
        setUpConnectedState(true, true);

        // Set the active device to something else, verify we're inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isFalse();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Now that we're inactive, receive a playback status of playing
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);

        // Verify we send a pause, never request audio focus, and the playback state on
        // BluetoothMediaBrowserService never updates.
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mA2dpSinkService, never()).requestAudioFocus(mDevice, true);

        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        assertThat(mediaBrowserService.getPlaybackState().getState())
                .isEqualTo(PlaybackStateCompat.STATE_ERROR);
    }

    /** Test receiving a play position update when we're not the active device */
    @Test
    public void testPlayPositionChangeWhileNotActiveDevice() {
        setUpConnectedState(true, true);

        // Set the active device to something else, verify we're inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isFalse();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Now that we're inactive, receive a play position change
        setPlaybackPosition(1, 10);

        // Since we're not active, verify BluetoothMediaBrowserService does not have these values
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        PlaybackStateCompat playbackState = mediaBrowserService.getPlaybackState();
        assertThat(playbackState).isNotNull();
        assertThat(playbackState.getPosition()).isEqualTo(0);
    }

    /** Test receiving a now playing list update when we're not the active device */
    @Test
    public void testNowPlayingListChangeWhileNotActiveDevice() {
        setUpConnectedState(true, true);

        // Set the active device to something else, verify we're inactive and send a pause upon
        // becoming inactive
        setActiveDevice(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        assertThat(mAvrcpStateMachine.isActive()).isFalse();

        // Change queue while inactive
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        AvrcpItem queueItem1 = makeNowPlayingItem(0, "title");
        AvrcpItem queueItem2 = makeNowPlayingItem(1, "title 2");
        AvrcpItem queueItem3 = makeNowPlayingItem(1, "title 3");
        nowPlayingList.add(queueItem1);
        nowPlayingList.add(queueItem2);
        nowPlayingList.add(queueItem3);
        setNowPlayingList(nowPlayingList);

        // Since we're not active, verify BluetoothMediaBrowserService does not have these values
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        assertThat(mediaBrowserService.getNowPlayingQueue()).isNull();
    }

    /**
     * Test receiving an audio focus changed event when we are not recovering from a transient loss.
     * This should result in no play command being sent.
     */
    @Test
    public void testOnAudioFocusGain_playNotSent() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /**
     * Test receiving a transient loss audio focus changed event while playing. A pause should be
     * sent
     */
    @Test
    public void testOnAudioFocusTransientLossWhilePlaying_pauseSent() {
        doReturn(false)
                .when(mMockResources)
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /**
     * Test receiving a transient loss audio focus changed event while paused. No pause should be
     * sent
     */
    @Test
    public void testOnAudioFocusTransientLossWhilePaused_pauseNotSent() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /** Test receiving an audio focus loss event. A pause should be sent if we were playing */
    @Test
    public void testOnAudioFocusLossWhilePlaying_pauseSent() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_LOSS);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /** Test receiving an audio focus loss event. A pause should not be sent if we were paused */
    @Test
    public void testOnAudioFocusLossWhilePause_pauseNotSent() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_LOSS);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, times(0))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface, times(0))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    /**
     * Test receiving an audio focus gained event following a transient loss where we sent a pause
     * and no event happened in between that should cause us to remain paused.
     */
    @Test
    public void testOnAudioFocusGainFromTransientLossWhilePlaying_playSent() {
        testOnAudioFocusTransientLossWhilePlaying_pauseSent();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /** Test receiving an audio focus changed event following a transient loss where */
    @Test
    public void testOnAudioFocusGainFromTransientLossWhilePlayingWithPause_playNotSent() {
        testOnAudioFocusTransientLossWhilePlaying_pauseSent();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        transportControls.pause();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));

        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /**
     * Test receiving an audio focus gain coming out of a transient loss where a stop command has
     * been sent
     */
    @Test
    public void testOnAudioFocusGainFromTransientLossWithStop_playNotSent() {
        testOnAudioFocusTransientLossWhilePlaying_pauseSent();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        transportControls.stop();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_DOWN));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_UP));

        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /**
     * Test receiving an audio focus gain coming out of a transient loss where we were paused going
     * into the transient loss. No play should be sent because not play state needs to be recovered
     */
    @Test
    public void testOnAudioFocusGainFromTransientLossWhilePaused_playNotSent() {
        testOnAudioFocusTransientLossWhilePaused_pauseNotSent();
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface, never())
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    /**
     * Test receiving a now playing content changed event while downloading now playing content and
     * make sure our final now playing content downloaded matches what's expected
     */
    @Test
    public void testNowPlayingListChangedWhileFetchingNowPlayingList_fetchAbortedAndRestarted() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        // Fill the list with songs 1 -> 25, more than download step size
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 1; i <= 25; i++) {
            String title = "Song " + Integer.toString(i);
            nowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Fill the list with songs 26 -> 50
        List<AvrcpItem> updatedNowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 26; i <= 50; i++) {
            String title = "Song " + Integer.toString(i);
            updatedNowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Hand hold the download events
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify download attempt and send some elements over, verify next set is requested
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(0, 20)));
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(20), eq(39));

        // Force a now playing content invalidation and verify attempted download
        mAvrcpStateMachine.nowPlayingContentChanged();
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Send requested items, they're likely from the new list at this point, but it shouldn't
        // matter what they are because we should toss them out and restart our download next.
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(20, 25)));
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        verify(mNativeInterface, times(2)).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(0, 20)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(20, 25)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        // Wait for the now playing list to be propagated
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure its set by re grabbing the node and checking its contents are cached
        nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        assertThat(nowPlaying.isCached()).isTrue();
        assertThat(getNowPlayingList()).containsExactlyElementsIn(updatedNowPlayingList).inOrder();
    }

    /**
     * Test receiving a now playing content changed event right after we queued a fetch of some now
     * playing items. Make sure our final now playing content downloaded matches what's expected
     */
    @Test
    public void testNowPlayingListChangedQueuedFetchingNowPlayingList_fetchAbortedAndRestarted() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        // Fill the list with songs 1 -> 25, more than download step size
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 1; i <= 25; i++) {
            String title = "Song " + Integer.toString(i);
            nowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Fill the list with songs 26 -> 50
        List<AvrcpItem> updatedNowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 26; i <= 50; i++) {
            String title = "Song " + Integer.toString(i);
            updatedNowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Hand hold the download events
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify download attempt and send some elements over, verify next set is requested
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        mAvrcpStateMachine.nowPlayingContentChanged();

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(0, 20)));
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Receiving the previous members should cause our fetch process to realize we're aborted
        // and a new (second) request should be triggered for the list from the beginning
        verify(mNativeInterface, times(2)).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        // Send whole list
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(0, 20)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(20, 25)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        // Wait for the now playing list to be propagated
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure its set by re grabbing the node and checking its contents are cached
        nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        assertThat(nowPlaying.isCached()).isTrue();
        assertThat(getNowPlayingList()).containsExactlyElementsIn(updatedNowPlayingList).inOrder();
    }

    /**
     * Test receiving an addressed player changed event while downloading now playing content and
     * make sure our final now playing content downloaded matches what's expected.
     */
    @Test
    public void testAddressedPlayerChangedWhileFetchingNowPlayingList_fetchAbortedAndRestarted() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        // Fill the list with songs 1 -> 25, more than download step size
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 1; i <= 25; i++) {
            String title = "Song " + Integer.toString(i);
            nowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Fill the list with songs 26 -> 50
        List<AvrcpItem> updatedNowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 26; i <= 50; i++) {
            String title = "Song " + Integer.toString(i);
            updatedNowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Hand hold the download events
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify download attempt and send some elements over, verify next set is requested
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(0, 20)));
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(20), eq(39));

        // Force a now playing content invalidation due to addressed player change
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 1);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Send requested items, they're likely from the new list at this point, but it shouldn't
        // matter what they are because we should toss them out and restart our download next.
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(20, 25)));
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        verify(mNativeInterface, times(2)).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(0, 20)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(20, 25)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        // Wait for the now playing list to be propagated
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure its set by re grabbing the node and checking its contents are cached
        nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        assertThat(nowPlaying.isCached()).isTrue();
        assertThat(getNowPlayingList()).containsExactlyElementsIn(updatedNowPlayingList).inOrder();
    }

    /**
     * Test receiving an addressed player changed event while downloading now playing content and
     * make sure our final now playing content downloaded matches what's expected.
     */
    @Test
    public void testAddressedPlayerChangedQueuedFetchingNowPlayingList_fetchAbortedAndRestarted() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        // Fill the list with songs 1 -> 25, more than download step size
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 1; i <= 25; i++) {
            String title = "Song " + Integer.toString(i);
            nowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Fill the list with songs 26 -> 50
        List<AvrcpItem> updatedNowPlayingList = new ArrayList<AvrcpItem>();
        for (int i = 26; i <= 50; i++) {
            String title = "Song " + Integer.toString(i);
            updatedNowPlayingList.add(makeNowPlayingItem(i, title));
        }

        // Hand hold the download events
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify download attempt and send some elements over, verify next set is requested
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        // Force a now playing content invalidation due to addressed player change, happening
        // before we've received any items from the remote device.
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 1);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Now, send the items in and let it process
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(nowPlayingList.subList(0, 20)));
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());

        verify(mNativeInterface, times(2)).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));

        // Send requested items, they're likely from the new list at this point, but it shouldn't
        // matter what they are because we should toss them out and restart our download next.
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(0, 20)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS,
                new ArrayList<AvrcpItem>(updatedNowPlayingList.subList(20, 25)));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);

        // Wait for the now playing list to be propagated
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure its set by re grabbing the node and checking its contents are cached
        nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        assertThat(nowPlaying.isCached()).isTrue();
        assertThat(getNowPlayingList()).containsExactlyElementsIn(updatedNowPlayingList).inOrder();
    }

    /**
     * Test making a browse request where results don't come back within the timeout window. The
     * node should still be notified on.
     */
    @Test
    public void testMakeBrowseRequestWithTimeout_contentsCachedAndNotified() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        // Set something arbitrary for the current Now Playing list
        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        nowPlayingList.add(makeNowPlayingItem(1, "Song 1"));
        setNowPlayingList(nowPlayingList);
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);

        // Invalidate the contents by doing a new fetch
        BrowseTree.BrowseNode nowPlaying = mAvrcpStateMachine.findNode("NOW_PLAYING");
        mAvrcpStateMachine.requestContents(nowPlaying);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Request for new contents should be sent
        verify(mNativeInterface).getNowPlayingList(eq(mTestAddress), eq(0), eq(19));
        assertThat(nowPlaying.isCached()).isFalse();

        // Send timeout on our own instead of waiting 10 seconds
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.MESSAGE_INTERNAL_CMD_TIMEOUT);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Node should be set to cached and notified on
        assertThat(getNowPlayingList()).isEmpty();

        assertThat(nowPlaying.isCached()).isTrue();

        // See that state from BluetoothMediaBrowserService is updated to null (i.e. empty)
        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        assertThat(mediaBrowserService.getNowPlayingQueue()).isNull();
    }

    /**
     * Test making a browse request with a null node. The request should not generate any native
     * layer browse requests.
     */
    @Test
    public void testNullBrowseRequest_requestDropped() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        mAvrcpStateMachine.requestContents(null);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verifyNoMoreInteractions(mAvrcpControllerService);
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test making a browse request with browsing disconnected. The request should not generate any
     * native layer browse requests.
     */
    @Test
    public void testBrowseRequestWhileDisconnected_requestDropped() {
        setUpConnectedState(true, false);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        BrowseTree.BrowseNode deviceRoot = mAvrcpStateMachine.mBrowseTree.mRootNode;
        mAvrcpStateMachine.requestContents(deviceRoot);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verifyNoMoreInteractions(mAvrcpControllerService);
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Queue a control channel connection event, a request while browse is disconnected, a browse
     * connection event, and then another browse request and be sure the final request still is sent
     */
    @Test
    public void testBrowseRequestWhileDisconnectedThenRequestWhileConnected_secondRequestSent() {
        setUpConnectedState(true, false);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);
        clearInvocations(mAvrcpControllerService);
        clearInvocations(mNativeInterface);
        BrowseTree.BrowseNode deviceRoot = mAvrcpStateMachine.mBrowseTree.mRootNode;
        mAvrcpStateMachine.requestContents(deviceRoot);
        // issues a player list fetch
        mAvrcpStateMachine.connect(true, true);
        TestUtils.waitForLooperToBeIdle(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface).getPlayerList(eq(mTestAddress), eq(0), eq(19));
    }

    @Test
    public void testBrowsingContentsOfOtherBrowsablePlayer_browsedPlayerUncached() {
        setUpConnectedState(true, true);
        sendAudioFocusUpdate(AudioManager.AUDIOFOCUS_GAIN);

        BrowseTree.BrowseNode results = mAvrcpStateMachine.mBrowseTree.mRootNode;

        // Request fetch the list of players
        BrowseTree.BrowseNode playerNodes = mAvrcpStateMachine.findNode(results.getID());
        mAvrcpStateMachine.requestContents(playerNodes);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getPlayerList(eq(mTestAddress), eq(0), eq(19));

        // Provide back two player objects
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, "player 1", playerFeatures, 1);
        AvrcpPlayer playerTwo = makePlayer(mDevice, 2, "player 2", playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        testPlayers.add(playerTwo);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS, testPlayers);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Verify that the player objects are both available and properly formatted
        playerNodes = mAvrcpStateMachine.findNode(results.getID());
        assertThat(playerNodes.isCached()).isTrue();
        assertThat(playerNodes.getChildren()).isNotNull();
        assertThat(playerNodes.getChildren()).hasSize(2);
        assertThat(playerNodes.getChildren().get(0).getMediaItem().toString())
                .isEqualTo("MediaItem{mFlags=1, mDescription=player 1, null, null}");
        assertThat(playerNodes.getChildren().get(1).getMediaItem().toString())
                .isEqualTo("MediaItem{mFlags=1, mDescription=player 2, null, null}");

        // Fetch contents of the first player object
        BrowseTree.BrowseNode playerOneNode =
                mAvrcpStateMachine.findNode(results.getChildren().get(0).getID());
        mAvrcpStateMachine.requestContents(playerOneNode);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setBrowsedPlayer(eq(mTestAddress), eq(1));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_BROWSED_PLAYER,
                /* items= */ 5,
                /* depth= */ 0);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .getFolderList(eq(mTestAddress), eq(0), eq(4));

        // Return some results for Player One
        List<AvrcpItem> testFolderContents = new ArrayList<AvrcpItem>();
        for (int i = 0; i < 5; i++) {
            String title = "Song " + Integer.toString(i);
            testFolderContents.add(makeNowPlayingItem(i, title));
        }
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS, testFolderContents);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // Make sure the player/folder is cached
        playerOneNode = mAvrcpStateMachine.findNode(results.getChildren().get(0).getID());
        assertThat(playerOneNode.isCached()).isTrue();

        // Browse to the Player Two
        BrowseTree.BrowseNode playerTwoNode =
                mAvrcpStateMachine.findNode(results.getChildren().get(1).getID());
        mAvrcpStateMachine.requestContents(playerTwoNode);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setBrowsedPlayer(eq(mTestAddress), eq(2));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_BROWSED_PLAYER,
                /* items= */ 5,
                /* depth= */ 0);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2))
                .getFolderList(eq(mTestAddress), eq(0), eq(4));

        // Make sure the first player is uncached
        playerOneNode = mAvrcpStateMachine.findNode(results.getChildren().get(0).getID());
        assertThat(playerOneNode.isCached()).isFalse();

        // Send items for Player Two
        testFolderContents = new ArrayList<AvrcpItem>();
        for (int i = 5; i < 10; i++) {
            String title = "Song " + Integer.toString(i);
            testFolderContents.add(makeNowPlayingItem(i, title));
        }
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS, testFolderContents);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        // make sure the second player is cached now
        playerTwoNode = mAvrcpStateMachine.findNode(results.getChildren().get(1).getID());
        assertThat(playerTwoNode.isCached()).isTrue();
    }

    @Test
    public void testOnShuffleStateChanged() {
        setUpConnectedState(true, true);
        doReturn(PlaybackStateCompat.SHUFFLE_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.SHUFFLE_STATUS);

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS,
                mPlayerApplicationSettings);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        assertThat(mediaBrowserService.getShuffleMode())
                .isEqualTo(PlaybackStateCompat.SHUFFLE_MODE_ALL);
    }

    @Test
    public void testOnRepeatStateChanged() {
        setUpConnectedState(true, true);
        doReturn(PlaybackStateCompat.REPEAT_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.REPEAT_STATUS);

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS,
                mPlayerApplicationSettings);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        BluetoothMediaBrowserService mediaBrowserService =
                BluetoothMediaBrowserService.getInstance();
        assertThat(mediaBrowserService).isNotNull();

        assertThat(mediaBrowserService.getRepeatMode())
                .isEqualTo(PlaybackStateCompat.REPEAT_MODE_ALL);
    }

    /**********************************************************************************************
     * MEDIA SOURCE TESTS                                                                         *
     *********************************************************************************************/

    // lifecycle

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testConnect_mediaSourceRegistered() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testDisconnect_mediaSourceUnregisteredPlaylistAndRootInvalidated() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();
        clearInvocations(mMediaSourceCallbacks);

        testDisconnectInternal();

        verify(mMediaSourceCallbacks).onNowPlayingListChanged(emptyList());
        verify(mMediaSourceCallbacks).onBrowseNodeChanged(any());
        verify(mMediaAudioServer).unregisterMediaSource(mMediaSource);
    }

    // Facade/Callbacks -> Metadata, PlaybackStatus, Queue

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testTrackChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        assertThat(mMediaSource.getMetadata()).isNull();
        clearInvocations(mMediaSourceCallbacks);

        AvrcpItem track = makeTrack("title", "artist", "album", 1, 10, "none", 10, null);
        setCurrentTrack(track);

        MediaSource.Metadata metadata = mMediaSource.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("title");
        assertThat(metadata.getArtist()).isEqualTo("artist");
        assertThat(metadata.getAlbum()).isEqualTo("album");
        assertThat(metadata.getTrackNumber()).isEqualTo(1);
        assertThat(metadata.getTotalNumberOfTracks()).isEqualTo(10);
        assertThat(metadata.getGenre()).isEqualTo("none");
        assertThat(metadata.getDuration()).isEqualTo(10);
        assertThat(metadata.getImageUri()).isNull();

        verify(mMediaSourceCallbacks).onMetadataChanged(metadata);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testCoverArtDownloaded_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();
        assertThat(mMediaSource.getMetadata()).isNull();
        clearInvocations(mMediaSourceCallbacks);

        String handle = "coverarthandle";
        String uuid = "coverartuuid";

        AvrcpItem track = makeTrack("title", "artist", "album", 1, 10, "none", 10, handle);
        track.setCoverArtUuid(uuid);
        setCurrentTrack(track);

        MediaSource.Metadata metadata = mMediaSource.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getImageUri()).isNull();
        verify(mCoverArtManager).downloadImage(mDevice, uuid);

        Uri uri = mock(Uri.class);
        doReturn(uri).when(mCoverArtManager).getImageUri(mDevice, uuid);

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_IMAGE_DOWNLOADED,
                new AvrcpCoverArtManager.DownloadEvent(uuid, uri));
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        metadata = mMediaSource.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getImageUri()).isEqualTo(uri);

        verify(mMediaSourceCallbacks).onMetadataChanged(metadata);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testPlaybackStateChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        MediaSource.PlaybackStatus status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNull();
        clearInvocations(mMediaSourceCallbacks);

        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);

        status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNotNull();
        assertThat(status.getState()).isEqualTo(MediaSource.PlaybackState.PLAYING);

        verify(mMediaSourceCallbacks).onPlaybackStatusChanged(status);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testPlaybackPositionChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        MediaSource.PlaybackStatus status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNull();
        clearInvocations(mMediaSourceCallbacks);

        setPlaybackPosition(/* position= */ 1, /* duration= */ 10);

        status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNotNull();
        assertThat(status.getPlaybackPosition()).isEqualTo(1);

        verify(mMediaSourceCallbacks).onPlaybackStatusChanged(status);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testNowPlayingQueueChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        List<MediaSource.Metadata> queue = mMediaSource.getNowPlayingList();
        assertThat(queue).isNotNull();
        assertThat(queue).isEmpty();
        clearInvocations(mMediaSourceCallbacks);

        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        AvrcpItem queueItem1 = makeNowPlayingItem(0, "title");
        AvrcpItem queueItem2 = makeNowPlayingItem(1, "title 2");
        AvrcpItem queueItem3 = makeNowPlayingItem(1, "title 3");
        nowPlayingList.add(queueItem1);
        nowPlayingList.add(queueItem2);
        nowPlayingList.add(queueItem3);
        setNowPlayingList(nowPlayingList);

        queue = mMediaSource.getNowPlayingList();
        assertThat(queue).isNotNull();
        assertThat(queue).hasSize(3);
        assertThat(queue.get(0).getTitle()).isEqualTo("title");
        assertThat(queue.get(1).getTitle()).isEqualTo("title 2");
        assertThat(queue.get(2).getTitle()).isEqualTo("title 3");

        verify(mMediaSourceCallbacks, atLeast(1)).onNowPlayingListChanged(queue);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testShuffleModeChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        MediaSource.PlaybackStatus status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNull();
        clearInvocations(mMediaSourceCallbacks);

        doReturn(PlaybackStateCompat.SHUFFLE_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.SHUFFLE_STATUS);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS,
                mPlayerApplicationSettings);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNotNull();
        assertThat(status.getShuffleMode()).isEqualTo(MediaSource.ShuffleMode.ALL);

        verify(mMediaSourceCallbacks).onPlaybackStatusChanged(status);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testRepeatModeChanged_mediaSourceUpdatedCallbackInvoked() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        MediaSource.PlaybackStatus status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNull();
        clearInvocations(mMediaSourceCallbacks);

        doReturn(PlaybackStateCompat.REPEAT_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.REPEAT_STATUS);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS,
                mPlayerApplicationSettings);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        status = mMediaSource.getPlaybackStatus();
        assertThat(status).isNotNull();
        assertThat(status.getRepeatMode()).isEqualTo(MediaSource.RepeatMode.ALL);

        verify(mMediaSourceCallbacks).onPlaybackStatusChanged(status);
    }

    // Playback Commands

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onPrepare_doesNothing() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onPrepare();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        // Does Nothing Now
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onPlay_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onPlay();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY),
                        eq(KEY_UP));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onPause_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onPause();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE),
                        eq(KEY_UP));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onSkipToNext_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onSkipToNext();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD),
                        eq(KEY_UP));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onSkipToPrevious_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onSkipToPrevious();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD),
                        eq(KEY_UP));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onSkipToQueueItem_playItemCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        List<AvrcpItem> nowPlayingList = new ArrayList<AvrcpItem>();
        AvrcpItem queueItem1 = makeNowPlayingItem(0, "title");
        nowPlayingList.add(queueItem1);
        setNowPlayingList(nowPlayingList);

        mMediaSource.onSkipToQueueItem(/* index= */ 0);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        int scope = AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING;
        verify(mNativeInterface).playItem(mTestAddress, (byte) scope, 0l, 0);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onStop_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onStop();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_DOWN));
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP),
                        eq(KEY_UP));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onRewind_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onRewind();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_REWIND),
                        eq(KEY_DOWN));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onFastForward_passthroughCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onFastForward();

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mNativeInterface)
                .sendPassThroughCommand(
                        eq(mTestAddress),
                        eq(AvrcpControllerService.PASS_THRU_CMD_ID_FF),
                        eq(KEY_DOWN));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onPlayFromMediaId_playItemCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        // Create a player to be returned
        final String playerName = "Player 1";
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, playerName, playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);

        // Force the fetch of the player item
        setAvailablePlayers(testPlayers);

        // Create contents for the player, including our playable node
        List<AvrcpItem> playerContents = new ArrayList<>();
        AvrcpItem track =
                makeTrack("TEST_MEDIA_ID", 0, "title", "artist", "album", 1, 10, "none", 10, null);
        playerContents.add(track);

        // Browse to the player to get a playable node in the tree
        BrowseTree.BrowseNode root = mAvrcpStateMachine.mBrowseTree.mRootNode;
        BrowseTree.BrowseNode playerOneNode =
                mAvrcpStateMachine.findNode(root.getChildren().get(0).getID());
        setFolderContents(playerOneNode, playerContents);

        // Request to play that node
        mMediaSource.onPlayFromMediaId("TEST_MEDIA_ID");

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        int scope = AvrcpControllerService.BROWSE_SCOPE_VFS;
        verify(mNativeInterface).playItem(mTestAddress, (byte) scope, 0l, 0);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onSetShufffleMode_playerApplicationSettingsCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onSetShuffleMode(MediaSource.ShuffleMode.ALL);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        byte[] shuffleSetting = new byte[] {3};
        byte[] shuffleModeAll = new byte[] {2};
        verify(mNativeInterface)
                .setPlayerApplicationSettingValues(
                        eq(mTestAddress), eq((byte) 1), eq(shuffleSetting), eq(shuffleModeAll));
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onSetRepeatMode_playerApplicationSettingsCommandSent() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mMediaSource.onSetRepeatMode(MediaSource.RepeatMode.ALL);

        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        byte[] repeatSetting = new byte[] {2};
        byte[] repeatModeAll = new byte[] {3};
        verify(mNativeInterface)
                .setPlayerApplicationSettingValues(
                        eq(mTestAddress), eq((byte) 1), eq(repeatSetting), eq(repeatModeAll));
    }

    // Browsing

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testMediaSource_onGetRoot_returnsRootNode() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        MediaSource.BrowseNode root = mMediaSource.getRoot();
        assertThat(root.getMediaId()).isEqualTo(mAvrcpStateMachine.mBrowseTree.mRootNode.getID());
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testOnAvailablePlayersChanged_onBrowseNodeChangedForRoot() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());

        verify(mMediaSourceCallbacks)
                .onBrowseNodeChanged(mAvrcpStateMachine.mBrowseTree.mRootNode.getID());
    }

    // media players received

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testOnGetPlayerItemsResponse_onBrowseNodeChangedForMediaPlayers() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();
        clearInvocations(mMediaSourceCallbacks);

        // Create a player to be returned
        final String playerName = "Player 1";
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, playerName, playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);

        // Force the fetch of the player item
        setAvailablePlayers(testPlayers);

        // Verify that the player object is available.
        BrowseTree.BrowseNode rootNode = mAvrcpStateMachine.mBrowseTree.mRootNode;
        assertThat(rootNode.isCached()).isTrue();
        assertThat(rootNode.getChildren().get(0).getMediaItem().toString())
                .isEqualTo("MediaItem{mFlags=1, mDescription=" + playerName + ", null, null}");

        verify(mMediaSourceCallbacks, atLeast(1)).onBrowseNodeChanged(rootNode.getID());
    }

    // browse node changed

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testOnGetFolderItemsResponse_onBrowseNodeChangedForFolder() {
        setUpConnectedState(true, true);
        assertThat(mMediaSource).isNotNull();

        // Create a player to be returned
        final String playerName = "Player 1";
        byte[] playerFeatures =
                new byte[] {0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = makePlayer(mDevice, 1, playerName, playerFeatures, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);

        // Force the fetch of the player item
        setAvailablePlayers(testPlayers);
        clearInvocations(mMediaSourceCallbacks);

        // Create contents for the player, including our playable node
        List<AvrcpItem> playerContents = new ArrayList<>();
        playerContents.add(makeNowPlayingItem(0, "title"));

        // Browse to the player to get a playable node in the tree
        BrowseTree.BrowseNode root = mAvrcpStateMachine.mBrowseTree.mRootNode;
        BrowseTree.BrowseNode playerOneNode =
                mAvrcpStateMachine.findNode(root.getChildren().get(0).getID());
        setFolderContents(playerOneNode, playerContents);

        // Assert that browse node contents changed for this player/folder
        verify(mMediaSourceCallbacks, atLeast(1)).onBrowseNodeChanged(playerOneNode.getID());
    }
}
