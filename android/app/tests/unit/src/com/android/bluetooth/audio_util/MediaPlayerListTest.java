/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.bluetooth.audio_util;

import static android.Manifest.permission.MEDIA_CONTENT_CONTROL;
import static android.Manifest.permission.MODIFY_PHONE_STATE;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MediaPlayerListTest {
    private MediaPlayerList mMediaPlayerList;

    private @Captor ArgumentCaptor<MediaPlayerWrapper.Callback> mPlayerWrapperCb;
    private @Captor ArgumentCaptor<MediaData> mMediaUpdateData;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private @Mock Context mMockContext;
    private @Mock MediaPlayerList.MediaUpdateCallback mMediaUpdateCallback;
    private @Mock MediaController mMockController;
    private @Mock MediaPlayerWrapper mMockPlayerWrapper;

    private MediaPlayerWrapper.Callback mActivePlayerCallback;
    private MediaSessionManager mMediaSessionManager;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(MEDIA_CONTENT_CONTROL, MODIFY_PHONE_STATE);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // MediaSessionManager is final and Bluetooth can't use extended Mockito to mock it. Thus,
        // using this as is risks leaking device state into the tests. To avoid this, the injected
        // controller and player below in the factory pattern will essentially replace each found
        // player with the *same* mock, giving us only one player in the end-- "testPlayer"
        mMediaSessionManager =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getSystemService(MediaSessionManager.class);
        PackageManager mockPackageManager = mock(PackageManager.class);
        mockGetSystemService(
                mMockContext,
                Context.MEDIA_SESSION_SERVICE,
                MediaSessionManager.class,
                mMediaSessionManager);
        mockGetSystemService(mMockContext, Context.AUDIO_SERVICE, AudioManager.class);

        when(mMockContext.registerReceiver(any(), any())).thenReturn(null);
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mockPackageManager);
        when(mockPackageManager.queryIntentServices(any(), anyInt())).thenReturn(null);

        BrowsablePlayerConnector mockConnector = mock(BrowsablePlayerConnector.class);
        BrowsablePlayerConnector.setInstanceForTesting(mockConnector);

        MediaControllerFactory.inject(mMockController);
        MediaPlayerWrapperFactory.inject(mMockPlayerWrapper);

        doReturn("testPlayer").when(mMockController).getPackageName();
        when(mMockPlayerWrapper.isMetadataSynced()).thenReturn(false);

        // Be sure to do this setup last, after factor injections, or you risk leaking device state
        // into the tests
        mMediaPlayerList =
                new MediaPlayerList(
                        Looper.myLooper(),
                        InstrumentationRegistry.getInstrumentation().getTargetContext());
        mMediaPlayerList.init(mMediaUpdateCallback);
        mMediaPlayerList.setActivePlayer(mMediaPlayerList.addMediaPlayer(mMockController));

        verify(mMockPlayerWrapper).registerCallback(mPlayerWrapperCb.capture());
        mActivePlayerCallback = mPlayerWrapperCb.getValue();
    }

    @After
    public void tearDown() throws Exception {
        BrowsablePlayerConnector.setInstanceForTesting(null);

        MediaControllerFactory.inject(null);
        MediaPlayerWrapperFactory.inject(null);
        mMediaPlayerList.cleanup();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static MediaData prepareMediaData(int playbackState) {
        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setState(playbackState, 0, 1);
        ArrayList<Metadata> list = new ArrayList<Metadata>();
        list.add(Util.empty_data());
        MediaData newData = new MediaData(Util.empty_data(), builder.build(), list);

        return newData;
    }

    @Test
    public void testUpdateMediaDataForAudioPlaybackWhenActivePlayNotPlaying() {
        // Verify update media data with playing state
        doReturn(prepareMediaData(PlaybackState.STATE_PAUSED))
                .when(mMockPlayerWrapper)
                .getCurrentMediaData();
        mMediaPlayerList.injectAudioPlaybacActive(true);
        verify(mMediaUpdateCallback).run(mMediaUpdateData.capture());
        MediaData data = mMediaUpdateData.getValue();
        assertThat(data.state.getState()).isEqualTo(PlaybackState.STATE_PLAYING);

        // verify update media data with current media player media data
        MediaData currentMediaData = prepareMediaData(PlaybackState.STATE_PAUSED);
        doReturn(currentMediaData).when(mMockPlayerWrapper).getCurrentMediaData();
        mMediaPlayerList.injectAudioPlaybacActive(false);
        verify(mMediaUpdateCallback, times(2)).run(mMediaUpdateData.capture());
        data = mMediaUpdateData.getValue();
        assertThat(data.metadata).isEqualTo(currentMediaData.metadata);
        assertThat(data.state.toString()).isEqualTo(currentMediaData.state.toString());
        assertThat(data.queue).isEqualTo(currentMediaData.queue);
    }

    @Test
    public void testUpdateMediaDataForActivePlayerWhenAudioPlaybackIsNotActive() {
        MediaData currMediaData = prepareMediaData(PlaybackState.STATE_PLAYING);
        mActivePlayerCallback.mediaUpdatedCallback(currMediaData);
        verify(mMediaUpdateCallback).run(currMediaData);

        currMediaData = prepareMediaData(PlaybackState.STATE_PAUSED);
        mActivePlayerCallback.mediaUpdatedCallback(currMediaData);
        verify(mMediaUpdateCallback).run(currMediaData);
    }

    @Test
    public void testNotUpdateMediaDataForAudioPlaybackWhenActivePlayerIsPlaying() {
        // Verify not update media data for Audio Playback when active player is playing
        doReturn(prepareMediaData(PlaybackState.STATE_PLAYING))
                .when(mMockPlayerWrapper)
                .getCurrentMediaData();
        mMediaPlayerList.injectAudioPlaybacActive(true);
        mMediaPlayerList.injectAudioPlaybacActive(false);
        verify(mMediaUpdateCallback, never()).run(any());
    }

    @Test
    public void testNotUpdateMediaDataForActivePlayerWhenAudioPlaybackIsActive() {
        doReturn(prepareMediaData(PlaybackState.STATE_PLAYING))
                .when(mMockPlayerWrapper)
                .getCurrentMediaData();
        mMediaPlayerList.injectAudioPlaybacActive(true);
        verify(mMediaUpdateCallback, never()).run(any());

        // Verify not update active player media data when audio playback is active
        mActivePlayerCallback.mediaUpdatedCallback(prepareMediaData(PlaybackState.STATE_PAUSED));
        verify(mMediaUpdateCallback, never()).run(any());
    }

    @Test
    public void testSkipGlobalPrioritySession() {
        // Store current active media player.
        MediaPlayerWrapper activeMediaPlayer = mMediaPlayerList.getActivePlayer();

        // Create MediaSession with GLOBAL_PRIORITY flag.
        MediaSession session =
                new MediaSession(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        MediaPlayerListTest.class.getSimpleName());
        session.setFlags(
                MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY
                        | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);

        // Use MediaPlayerList onMediaKeyEventSessionChanged callback to send the new session.
        mMediaPlayerList.mMediaKeyEventSessionChangedListener.onMediaKeyEventSessionChanged(
                session.getController().getPackageName(), session.getSessionToken());

        // Retrieve the current available controllers
        ArrayList<android.media.session.MediaController> currentControllers =
                new ArrayList<android.media.session.MediaController>(
                        mMediaSessionManager.getActiveSessions(null));
        // Add the new session
        currentControllers.add(session.getController());
        // Use MediaPlayerList onActiveSessionsChanged callback to send the new session.
        mMediaPlayerList.mActiveSessionsChangedListener.onActiveSessionsChanged(currentControllers);

        // Retrieve the new active MediaSession.
        MediaPlayerWrapper newActiveMediaPlayer = mMediaPlayerList.getActivePlayer();

        // Should be the same as before.
        assertThat(activeMediaPlayer).isEqualTo(newActiveMediaPlayer);

        session.release();
    }
}
