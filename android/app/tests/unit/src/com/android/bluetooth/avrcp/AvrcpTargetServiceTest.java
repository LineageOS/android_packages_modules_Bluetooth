/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import static android.Manifest.permission.MEDIA_CONTENT_CONTROL;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.UserManager;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.audio_util.Image;
import com.android.bluetooth.audio_util.Metadata;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpTargetServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mMockAdapterService;
    @Mock private AudioManager mMockAudioManager;
    @Mock private AvrcpNativeInterface mMockNativeInterface;
    @Mock private Resources mMockResources;
    @Mock private SharedPreferences mMockSharedPreferences;
    @Mock private SharedPreferences.Editor mMockSharedPreferencesEditor;

    @Captor private ArgumentCaptor<AudioDeviceCallback> mAudioDeviceCb;

    private static final String TEST_DATA = "-1";

    private final MediaSessionManager mMediaSessionManager =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(MediaSessionManager.class);

    private TestLooper mLooper;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(MEDIA_CONTENT_CONTROL);
        mLooper = new TestLooper();
        mLooper.startAutoDispatch();

        mockGetSystemService(
                mMockAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mMockAudioManager);

        mockGetSystemService(
                mMockAdapterService,
                Context.MEDIA_SESSION_SERVICE,
                MediaSessionManager.class,
                mMediaSessionManager);

        doReturn(mLooper.getNewExecutor()).when(mMockAdapterService).getMainExecutor();

        doReturn(mMockAdapterService).when(mMockAdapterService).getApplicationContext();
        mockGetSystemService(mMockAdapterService, Context.USER_SERVICE, UserManager.class);
        doReturn(mMockResources).when(mMockAdapterService).getResources();

        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferences).edit();
        doReturn(mMockSharedPreferences)
                .when(mMockAdapterService)
                .getSharedPreferences(anyString(), anyInt());
    }

    @After
    public void tearDown() throws Exception {
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testQueueUpdateData() {
        List<Metadata> firstQueue = new ArrayList<Metadata>();
        List<Metadata> secondQueue = new ArrayList<Metadata>();

        firstQueue.add(createEmptyMetadata());
        secondQueue.add(createEmptyMetadata());
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isFalse();

        secondQueue.add(createEmptyMetadata());
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();

        firstQueue.add(createEmptyMetadata());
        firstQueue.get(1).album = TEST_DATA;
        firstQueue.get(1).genre = TEST_DATA;
        firstQueue.get(1).mediaId = TEST_DATA;
        firstQueue.get(1).trackNum = TEST_DATA;
        firstQueue.get(1).numTracks = TEST_DATA;
        firstQueue.get(1).duration = TEST_DATA;
        firstQueue.get(1).image =
                new Image(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), Uri.EMPTY);
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isFalse();

        secondQueue.get(1).title = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();

        secondQueue.set(1, createEmptyMetadata());
        secondQueue.get(1).artist = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();
    }

    private static Metadata createEmptyMetadata() {
        Metadata.Builder builder = new Metadata.Builder();
        return builder.useDefaults().build();
    }

    @Test
    public void testServiceInstance() {
        AvrcpVolumeManager volumeManager =
                new AvrcpVolumeManager(
                        mMockAdapterService, mMockAudioManager, mMockNativeInterface);
        AvrcpTargetService service =
                new AvrcpTargetService(
                        mMockAdapterService,
                        mMockAudioManager,
                        mMockNativeInterface,
                        volumeManager,
                        mLooper.getLooper());

        verify(mMockAudioManager)
                .registerAudioDeviceCallback(mAudioDeviceCb.capture(), anyObject());

        service.cleanup();
        assertThat(mAudioDeviceCb.getValue()).isNotNull();
        verify(mMockAudioManager).unregisterAudioDeviceCallback(mAudioDeviceCb.getValue());
    }
}
