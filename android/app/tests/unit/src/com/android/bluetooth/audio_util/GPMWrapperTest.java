/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link GPMWrapper}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GPMWrapperTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private MediaController mMediaController;

    @Before
    public void setUp() throws Exception {
        mMediaController = mock(MediaController.class);
    }

    @Test
    public void isMetadataSynced_whenQueueIsNull_returnsFalse() {
        doReturn(null).when(mMediaController).getQueue();

        GPMWrapper wrapper = new GPMWrapper(mContext, mMediaController, null);

        assertThat(wrapper.isMetadataSynced()).isFalse();
    }

    @Test
    public void isMetadataSynced_whenOutOfSync_returnsFalse() {
        long activeQueueItemId = 3;
        PlaybackState state =
                new PlaybackState.Builder().setActiveQueueItemId(activeQueueItemId).build();
        doReturn(state).when(mMediaController).getPlaybackState();

        List<MediaSession.QueueItem> queue = new ArrayList<>();
        MediaDescription description =
                new MediaDescription.Builder().setTitle("Title from queue item").build();
        MediaSession.QueueItem queueItem =
                new MediaSession.QueueItem(description, activeQueueItemId);
        queue.add(queueItem);
        doReturn(queue).when(mMediaController).getQueue();

        MediaMetadata metadata =
                new MediaMetadata.Builder()
                        .putString(
                                MediaMetadata.METADATA_KEY_TITLE,
                                "Different Title from MediaMetadata")
                        .build();
        doReturn(metadata).when(mMediaController).getMetadata();

        GPMWrapper wrapper = new GPMWrapper(mContext, mMediaController, null);

        assertThat(wrapper.isMetadataSynced()).isFalse();
    }

    @Test
    public void isMetadataSynced_whenSynced_returnsTrue() {
        String title = "test_title";

        long activeQueueItemId = 3;
        PlaybackState state =
                new PlaybackState.Builder().setActiveQueueItemId(activeQueueItemId).build();
        doReturn(state).when(mMediaController).getPlaybackState();

        List<MediaSession.QueueItem> queue = new ArrayList<>();
        MediaDescription description = new MediaDescription.Builder().setTitle(title).build();
        MediaSession.QueueItem queueItem =
                new MediaSession.QueueItem(description, activeQueueItemId);
        queue.add(queueItem);
        doReturn(queue).when(mMediaController).getQueue();

        MediaMetadata metadata =
                new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .build();
        doReturn(metadata).when(mMediaController).getMetadata();

        GPMWrapper wrapper = new GPMWrapper(mContext, mMediaController, null);

        assertThat(wrapper.isMetadataSynced()).isTrue();
    }
}
