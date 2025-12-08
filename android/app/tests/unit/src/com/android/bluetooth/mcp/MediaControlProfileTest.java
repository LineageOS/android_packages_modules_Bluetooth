/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.bluetooth.mcp;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.PlaybackState;
import android.os.ParcelUuid;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.audio_util.MediaData;
import com.android.bluetooth.audio_util.MediaPlayerList;
import com.android.bluetooth.audio_util.MediaPlayerWrapper;
import com.android.bluetooth.audio_util.Metadata;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.UUID;

/** Test cases for {@link MediaControlProfile}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class MediaControlProfileTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private MediaData mMediaData;
    @Mock private MediaPlayerList mMediaPlayerList;
    @Mock private Metadata mMetadata;
    @Mock private MediaPlayerWrapper mMediaPlayerWrapper;
    @Mock private PackageManager mPackageManager;
    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private MediaControlGattServiceInterface mGMcsService;
    @Mock private McpService mMcpService;

    @Captor private ArgumentCaptor<HashMap> stateMapCaptor;
    @Captor private ArgumentCaptor<Long> positionCaptor;
    @Captor private ArgumentCaptor<MediaControlProfile.ListCallback> listCallbackCaptor;

    private MediaControlProfile mMediaControlProfile;
    private MediaControlServiceCallbacks mMcpServiceCallbacks;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MediaControlProfile.ListCallback listCallback;

        mMediaData.metadata = mMetadata;

        mMetadata.duration = "0";
        doReturn(mMediaPlayerWrapper).when(mMediaPlayerList).getActivePlayer();
        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        String packageName = "TestPackage";
        doReturn(packageName).when(mAdapterService).getPackageName();
        doReturn("TestPlayer").when(mMediaPlayerWrapper).getPackageName();
        doReturn("TestPlayer").when(mApplicationInfo).loadLabel(any(PackageManager.class));
        doReturn(mApplicationInfo).when(mPackageManager).getApplicationInfo(anyString(), anyInt());

        mMediaControlProfile = new MediaControlProfile(mAdapterService, mMediaPlayerList);

        // this is equivalent of what usually happens inside init class
        mMediaControlProfile.injectGattServiceForTesting(packageName, mGMcsService);
        mMediaControlProfile.onServiceInstanceRegistered(ServiceStatus.OK, mGMcsService);
        mMcpServiceCallbacks = mMediaControlProfile;

        // Make sure callbacks are not called before it's fully initialized
        verify(mMediaPlayerList, never()).init(any());
        mMediaControlProfile.init(mMcpService);
        verify(mMediaPlayerList).init(listCallbackCaptor.capture());

        listCallback = listCallbackCaptor.getValue();
        listCallback.run(mMediaData);
        // Give some time to verify if post function finishes on update player state method call
        // TODO: Is there a possibility to get rid of this timeout?
        verify(mGMcsService, timeout(100).times(1)).updatePlayerState(any(HashMap.class));
    }

    @After
    public void tearDown() {
        mMediaControlProfile.cleanup();
    }

    @Test
    public void testGetCurrentTrackDuration() {
        long duration = 10;

        // Some duration
        mMetadata.duration = Long.toString(duration);
        assertThat(mMediaControlProfile.getCurrentTrackDuration()).isEqualTo(duration);

        // No metadata equals no track duration
        mMediaData.metadata = null;
        assertThat(mMediaControlProfile.getCurrentTrackDuration())
                .isEqualTo(MediaControlGattServiceInterface.TRACK_DURATION_UNAVAILABLE);
    }

    @Test
    public void testPlayerState2McsState() {
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_PLAYING))
                .isEqualTo(MediaState.PLAYING);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_NONE))
                .isEqualTo(MediaState.INACTIVE);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_STOPPED))
                .isEqualTo(MediaState.PAUSED);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_PAUSED))
                .isEqualTo(MediaState.PAUSED);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_PLAYING))
                .isEqualTo(MediaState.PLAYING);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_FAST_FORWARDING))
                .isEqualTo(MediaState.SEEKING);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_REWINDING))
                .isEqualTo(MediaState.SEEKING);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_BUFFERING))
                .isEqualTo(MediaState.PAUSED);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_ERROR))
                .isEqualTo(MediaState.INACTIVE);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_CONNECTING))
                .isEqualTo(MediaState.INACTIVE);
        assertThat(
                        mMediaControlProfile.playerState2McsState(
                                PlaybackState.STATE_SKIPPING_TO_PREVIOUS))
                .isEqualTo(MediaState.PAUSED);
        assertThat(mMediaControlProfile.playerState2McsState(PlaybackState.STATE_SKIPPING_TO_NEXT))
                .isEqualTo(MediaState.PAUSED);
        assertThat(
                        mMediaControlProfile.playerState2McsState(
                                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM))
                .isEqualTo(MediaState.PAUSED);
    }

    @Test
    public void testGetLatestTrackPosition() {
        assertThat(mMcpServiceCallbacks.onGetCurrentTrackPosition())
                .isEqualTo(MediaControlGattServiceInterface.TRACK_POSITION_UNAVAILABLE);

        mMediaData.state =
                new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 10, 1.5f).build();
        doReturn(mMediaData.state).when(mMediaPlayerWrapper).getPlaybackState();

        assertThat(mMcpServiceCallbacks.onGetCurrentTrackPosition())
                .isNotEqualTo(MediaControlGattServiceInterface.TRACK_POSITION_UNAVAILABLE);
    }

    @Test
    public void testOnCurrentPlayerStateUpdate() {
        HashMap stateMap;
        int state = PlaybackState.STATE_PLAYING;
        long position = 10;
        float playback_speed = 1.5f;
        long update_time = 77;
        long duration = 10;
        String title = "TestTrackTitle";

        mMetadata.duration = Long.toString(duration);
        mMetadata.title = title;

        mMediaData.state =
                new PlaybackState.Builder(mMediaData.state)
                        .setState(state, position, playback_speed, update_time)
                        .build();

        mMediaControlProfile.onCurrentPlayerStateUpdated(true, true);
        // First time called from ListCallback. Give some time to verify if post function
        // finishes on update player state method call
        // TODO: Is there a possibility to get rid of this timeout?
        verify(mGMcsService, timeout(100).times(2)).updatePlayerState(stateMapCaptor.capture());
        stateMap = stateMapCaptor.getValue();

        assertThat(stateMap).containsKey(PlayerStateField.PLAYER_NAME);

        // state changed
        assertThat(stateMap).containsKey(PlayerStateField.PLAYBACK_STATE);
        assertThat(stateMap).containsKey(PlayerStateField.OPCODES_SUPPORTED);
        assertThat(stateMap).containsKey(PlayerStateField.SEEKING_SPEED);
        assertThat(stateMap).containsKey(PlayerStateField.PLAYBACK_SPEED);
        assertThat(stateMap).containsKey(PlayerStateField.TRACK_POSITION);

        // metadata changed
        assertThat(stateMap).containsKey(PlayerStateField.TRACK_DURATION);
        assertThat(stateMap).containsKey(PlayerStateField.TRACK_TITLE);
    }

    private void testHandleTrackPositionSetRequest(long position, long duration, int times) {
        mMcpServiceCallbacks.onTrackPositionSetRequest(position);
        verify(mMediaPlayerWrapper, timeout(100).times(times)).seekTo(positionCaptor.capture());

        // position cannot be negative and bigger than track duration
        if (position < 0) assertThat(positionCaptor.getValue().longValue()).isEqualTo(0);
        else if (position > duration) {
            assertThat(positionCaptor.getValue().longValue()).isEqualTo(duration);
        } else {
            assertThat(positionCaptor.getValue().longValue()).isEqualTo(position);
        }
    }

    @Test
    public void testHandleTrackPositionsSetRequest() {
        long duration = 50;
        int times = 1;

        mMetadata.duration = Long.toString(duration);

        mMediaData.state =
                new PlaybackState.Builder().setActions(PlaybackState.ACTION_SEEK_TO).build();

        testHandleTrackPositionSetRequest(-duration, duration, times++);
        testHandleTrackPositionSetRequest(duration + duration, duration, times++);
        testHandleTrackPositionSetRequest(duration / 2, duration, times++);

        mMediaData.state = new PlaybackState.Builder(mMediaData.state).setActions(0).build();

        mMcpServiceCallbacks.onTrackPositionSetRequest(duration);
        // First time called from ListCallback. Give some time to verify if post function
        // finishes on update player state method call
        // TODO: Is there a possibility to get rid of this timeout?
        verify(mGMcsService, timeout(100).times(2)).updatePlayerState(any(HashMap.class));
    }

    @Test
    public void testHandlePlaybackSpeedSetRequest() {
        float speed = 1.5f;
        int times = 1;

        mMcpServiceCallbacks.onPlaybackSpeedSetRequest(speed);
        verify(mMediaPlayerWrapper, timeout(100).times(times)).setPlaybackSpeed(anyFloat());

        // Playback speed wouldn't be set if no active player
        doReturn(null).when(mMediaPlayerList).getActivePlayer();
        mMcpServiceCallbacks.onPlaybackSpeedSetRequest(speed);
        verify(mMediaPlayerWrapper, timeout(100).times(times)).setPlaybackSpeed(anyFloat());
    }

    @Test
    public void testHandleMediaControlRequest() {
        long actions =
                PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_REWIND
                        | PlaybackState.ACTION_FAST_FORWARD
                        | PlaybackState.ACTION_SEEK_TO;
        long duration = 10;

        mMediaData.state = new PlaybackState.Builder().setActions(actions).build();

        Request request = new Request(Request.Opcodes.PLAY, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).playCurrent();
        request = new Request(Request.Opcodes.PAUSE, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).pauseCurrent();
        request = new Request(Request.Opcodes.STOP, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).seekTo(0);
        verify(mMediaPlayerWrapper).stopCurrent();
        request = new Request(Request.Opcodes.PREVIOUS_TRACK, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).skipToPrevious();
        request = new Request(Request.Opcodes.NEXT_TRACK, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).skipToNext();
        request = new Request(Request.Opcodes.FAST_REWIND, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).rewind();
        request = new Request(Request.Opcodes.FAST_FORWARD, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).fastForward();

        mMetadata.duration = Long.toString(duration);
        assertThat(mMediaControlProfile.getCurrentTrackDuration()).isEqualTo(duration);
        request = new Request(Request.Opcodes.MOVE_RELATIVE, 100);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).seekTo(duration);

        // Verify toggle-style play/pause control support
        clearInvocations(mMediaPlayerWrapper);
        mMediaData.state =
                new PlaybackState.Builder(mMediaData.state)
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                        .build();

        request = new Request(Request.Opcodes.PLAY, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).playCurrent();
        request = new Request(Request.Opcodes.PAUSE, 0);
        mMcpServiceCallbacks.onMediaControlRequest(request);
        verify(mMediaPlayerWrapper, timeout(100)).pauseCurrent();
    }

    @Test
    public void testAvrcpCompatibleActionSet() {
        long actions = PlaybackState.ACTION_SET_RATING;
        mMediaData.state = new PlaybackState.Builder().setActions(actions).build();

        // Same base feature set as the player item features defined in `avrcp/get_foder_items.cc`
        final long baseFeatures =
                PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_REWIND
                        | PlaybackState.ACTION_FAST_FORWARD
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        assertThat(mMediaControlProfile.getCurrentPlayerSupportedActions())
                .isEqualTo(actions | baseFeatures);
    }

    @Test
    public void testPlayerActions2McsSupportedOpcodes() {
        long actions =
                PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_REWIND
                        | PlaybackState.ACTION_FAST_FORWARD
                        | PlaybackState.ACTION_SEEK_TO;
        int opcodes_supported =
                Request.SupportedOpcodes.STOP
                        | Request.SupportedOpcodes.PAUSE
                        | Request.SupportedOpcodes.PLAY
                        | Request.SupportedOpcodes.FAST_REWIND
                        | Request.SupportedOpcodes.PREVIOUS_TRACK
                        | Request.SupportedOpcodes.NEXT_TRACK
                        | Request.SupportedOpcodes.FAST_FORWARD
                        | Request.SupportedOpcodes.MOVE_RELATIVE;

        assertThat(mMediaControlProfile.playerActions2McsSupportedOpcodes(actions))
                .isEqualTo(opcodes_supported);

        // Verify toggle-style play/pause control support
        actions = PlaybackState.ACTION_PLAY_PAUSE;
        opcodes_supported = Request.SupportedOpcodes.PAUSE | Request.SupportedOpcodes.PLAY;

        assertThat(mMediaControlProfile.playerActions2McsSupportedOpcodes(actions))
                .isEqualTo(opcodes_supported);
    }

    @Test
    public void testProcessPendingPlayerStateRequest() {
        mMediaData.state =
                new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 10, 1.5f).build();
        doReturn(mMediaData.state).when(mMediaPlayerWrapper).getPlaybackState();

        PlayerStateField[] state_fields =
                new PlayerStateField[] {
                    PlayerStateField.PLAYBACK_STATE,
                    PlayerStateField.TRACK_DURATION,
                    PlayerStateField.PLAYBACK_SPEED,
                    PlayerStateField.SEEKING_SPEED,
                    PlayerStateField.PLAYING_ORDER,
                    PlayerStateField.TRACK_POSITION,
                    PlayerStateField.PLAYER_NAME,
                    PlayerStateField.PLAYING_ORDER_SUPPORTED,
                    PlayerStateField.OPCODES_SUPPORTED
                };

        mMcpServiceCallbacks.onPlayerStateRequest(state_fields);
        // First time called from ListCallback. Give some time to verify if post function
        // finishes on update player state method call
        // TODO: Is there a possibility to get rid of this timeout?
        verify(mGMcsService, timeout(100).times(2)).updatePlayerState(stateMapCaptor.capture());
        HashMap stateMap = stateMapCaptor.getValue();

        assertThat(stateMap).containsKey(PlayerStateField.PLAYBACK_STATE);
        assertThat(stateMap).containsKey(PlayerStateField.TRACK_DURATION);
        assertThat(stateMap).containsKey(PlayerStateField.PLAYBACK_SPEED);
        assertThat(stateMap).containsKey(PlayerStateField.SEEKING_SPEED);
        assertThat(stateMap).containsKey(PlayerStateField.PLAYING_ORDER);
        assertThat(stateMap).containsKey(PlayerStateField.TRACK_POSITION);
        assertThat(stateMap).containsKey(PlayerStateField.PLAYER_NAME);
        assertThat(stateMap).containsKey(PlayerStateField.PLAYING_ORDER_SUPPORTED);
        assertThat(stateMap).containsKey(PlayerStateField.OPCODES_SUPPORTED);
    }

    private void testGetCurrentPlayerPlayingOrder(
            PlayingOrder expected_value, boolean is_shuffle_set, boolean is_repeat_set) {
        doReturn(is_shuffle_set).when(mMediaPlayerWrapper).isShuffleSet();
        doReturn(is_repeat_set).when(mMediaPlayerWrapper).isRepeatSet();
        assertThat(mMediaControlProfile.getCurrentPlayerPlayingOrder()).isEqualTo(expected_value);
    }

    @Test
    public void testGetCurrentPlayerPlayingOrders() {
        testGetCurrentPlayerPlayingOrder(PlayingOrder.SHUFFLE_REPEAT, true, true);
        testGetCurrentPlayerPlayingOrder(PlayingOrder.SHUFFLE_ONCE, true, false);
        testGetCurrentPlayerPlayingOrder(PlayingOrder.IN_ORDER_REPEAT, false, true);
        testGetCurrentPlayerPlayingOrder(PlayingOrder.IN_ORDER_ONCE, false, false);
    }

    private void testGetSupportedPlayingOrder(boolean is_shuffle_set, boolean is_repeat_set) {
        int expected_value = SupportedPlayingOrder.IN_ORDER_ONCE;

        if (is_repeat_set) expected_value |= SupportedPlayingOrder.IN_ORDER_REPEAT;
        if (is_shuffle_set) {
            if (is_repeat_set) expected_value |= SupportedPlayingOrder.SHUFFLE_REPEAT;
            else expected_value |= SupportedPlayingOrder.SHUFFLE_ONCE;
        }

        doReturn(is_shuffle_set).when(mMediaPlayerWrapper).isShuffleSupported();
        doReturn(is_repeat_set).when(mMediaPlayerWrapper).isRepeatSupported();
        assertThat(mMediaControlProfile.getSupportedPlayingOrder().intValue())
                .isEqualTo(expected_value);
    }

    @Test
    public void testGetSupportedPlayingOrders() {
        testGetSupportedPlayingOrder(true, true);
        testGetSupportedPlayingOrder(true, false);
        testGetSupportedPlayingOrder(false, true);
        testGetSupportedPlayingOrder(false, false);
    }

    @Test
    public void testGmcsSetGetNotificationSubscriptionDoesNotCrash() {
        final ParcelUuid charUuid1 = new ParcelUuid(UUID.randomUUID());
        final int ccid1 = BluetoothDevice.METADATA_GMCS_CCCD;

        doReturn(ccid1).when(mGMcsService).getContentControlId();
        doReturn(BluetoothUuid.GENERIC_MEDIA_CONTROL.getUuid()).when(mGMcsService).getServiceUuid();

        // BluetoothDevice class is not mockable
        BluetoothDevice bluetoothDevice = getTestDevice(0);
        mMediaControlProfile.setNotificationSubscription(ccid1, bluetoothDevice, charUuid1, true);
        assertThat(mMediaControlProfile.getNotificationSubscriptions(ccid1, bluetoothDevice))
                .isNotNull();
    }

    @Test
    public void testDumpDoesNotCrash() {
        mMediaControlProfile.dump(new StringBuilder());
    }
}
