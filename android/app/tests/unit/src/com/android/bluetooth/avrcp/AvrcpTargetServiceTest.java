/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.avrcp.AvrcpVersion.AVRCP_VERSION_1_5_STRING;
import static com.android.bluetooth.avrcp.AvrcpVersion.AVRCP_VERSION_PROPERTY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.SystemProperties;
import android.os.UserManager;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.audio_util.Image;
import com.android.bluetooth.audio_util.Metadata;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Test cases for {@link AvrcpTargetService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpTargetServiceTest {
    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Mock private AdapterService mAdapterService;
    @Mock private BluetoothStorageManager mStorage;
    @Mock private A2dpService mA2dpService;
    @Mock private AudioManager mAudioManager;
    @Mock private AvrcpNativeInterface mNativeInterface;
    @Mock private BluetoothDevice mMockDevice;
    @Mock private Resources mResources;
    @Mock private SharedPreferences mSharedPreferences;
    @Mock private SharedPreferences.Editor mSharedPreferencesEditor;
    @Mock private UserManager mUserManager;

    @Captor private ArgumentCaptor<AudioDeviceCallback> mAudioDeviceCb;
    @Captor private ArgumentCaptor<KeyEvent> mKeyEventCaptor;

    // Passthrough commands - must match AvrcpPassthrough defines
    private static final int PASSTHROUGH_ID_PLAY = 0x44;
    private static final int PASSTHROUGH_ID_STOP = 0x45;
    private static final int PASSTHROUGH_ID_PAUSE = 0x46;

    private static final String TEST_DATA = "-1";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final MediaSessionManager mMediaSessionManager =
            mContext.getSystemService(MediaSessionManager.class);

    private TestLooper mLooper;
    private AvrcpTargetService mService;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(MEDIA_CONTENT_CONTROL);
        mLooper = new TestLooper();
        mLooper.startAutoDispatch();

        mockSystemPropertyGet(AVRCP_VERSION_PROPERTY, AVRCP_VERSION_1_5_STRING);
        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        mockGetSystemService(mAdapterService, MediaSessionManager.class, mMediaSessionManager);

        doReturn(mLooper.getNewExecutor()).when(mAdapterService).getMainExecutor();

        doReturn(mContext).when(mAdapterService).getApplicationContext();
        doReturn(mResources).when(mAdapterService).getResources();

        doReturn(mSharedPreferencesEditor).when(mSharedPreferences).edit();
        doReturn(mSharedPreferences)
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());

        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                .when(mA2dpService)
                .getConnectionPolicy(any());
        doReturn(true).when(mUserManager).isUserUnlocked();

        AvrcpVolumeManager volumeManager =
                new AvrcpVolumeManager(mAdapterService, mStorage, mNativeInterface);
        mService =
                new AvrcpTargetService(
                        mAdapterService,
                        mStorage,
                        mAudioManager,
                        mNativeInterface,
                        volumeManager,
                        mUserManager,
                        mLooper.getLooper());

        // Verify that the service registers an audio device callback upon creation.
        verify(mAudioManager).registerAudioDeviceCallback(mAudioDeviceCb.capture(), any());
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        // Verify that the service unregisters the audio device callback upon cleanup.
        assertThat(mAudioDeviceCb.getValue()).isNotNull();
        verify(mAudioManager).unregisterAudioDeviceCallback(mAudioDeviceCb.getValue());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testQueueUpdateData() {
        List<Metadata> firstQueue = new ArrayList<>();
        List<Metadata> secondQueue = new ArrayList<>();

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
        firstQueue.get(1).image = new Image(mContext, Uri.EMPTY);
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isFalse();

        secondQueue.get(1).title = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();

        secondQueue.set(1, createEmptyMetadata());
        secondQueue.get(1).artist = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();
    }

    @Test
    public void sendMediaKeyEvent_whenVoiceActive_ignoresPlay() {
        setupVoiceCommunication(true);

        // Act: Send a PLAY key event.
        mService.sendMediaKeyEvent(mMockDevice, PASSTHROUGH_ID_PLAY, true);

        // Assert: The event is not dispatched to AudioManager.
        verify(mAudioManager, never()).dispatchMediaKeyEvent(any());
    }

    @Test
    public void sendMediaKeyEvent_whenVoiceActive_ignoresStop() {
        setupVoiceCommunication(true);

        // Act: Send a STOP key event.
        mService.sendMediaKeyEvent(mMockDevice, PASSTHROUGH_ID_STOP, true);

        // Assert: The event is not dispatched to AudioManager.
        verify(mAudioManager, never()).dispatchMediaKeyEvent(any());
    }

    @Test
    public void sendMediaKeyEvent_whenVoiceActive_dispatchesPause() {
        setupVoiceCommunication(true);

        // Act: Send a PAUSE key event.
        mService.sendMediaKeyEvent(mMockDevice, PASSTHROUGH_ID_PAUSE, true);

        // Assert: The event is dispatched to AudioManager with the correct key code.
        verify(mAudioManager).dispatchMediaKeyEvent(mKeyEventCaptor.capture());
        assertThat(mKeyEventCaptor.getValue().getKeyCode()).isEqualTo(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    @Test
    public void sendMediaKeyEvent_whenVoiceInactive_dispatchesPlay() {
        setupVoiceCommunication(false);

        // Act: Send a PLAY key event.
        mService.sendMediaKeyEvent(mMockDevice, PASSTHROUGH_ID_PLAY, true);

        // Assert: The event is dispatched to AudioManager with the correct key code.
        verify(mAudioManager).dispatchMediaKeyEvent(mKeyEventCaptor.capture());
        assertThat(mKeyEventCaptor.getValue().getKeyCode()).isEqualTo(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    @Test
    public void sendMediaKeyEvent_whenVoiceInactive_dispatchesStop() {
        setupVoiceCommunication(false);

        // Act: Send a STOP key event.
        mService.sendMediaKeyEvent(mMockDevice, PASSTHROUGH_ID_STOP, true);

        // Assert: The event is dispatched to AudioManager with the correct key code.
        verify(mAudioManager).dispatchMediaKeyEvent(mKeyEventCaptor.capture());
        assertThat(mKeyEventCaptor.getValue().getKeyCode()).isEqualTo(KeyEvent.KEYCODE_MEDIA_STOP);
    }

    private static Metadata createEmptyMetadata() {
        Metadata.Builder builder = new Metadata.Builder();
        return builder.useDefaults().build();
    }

    private void setupVoiceCommunication(boolean isActive) {
        if (!isActive) {
            doReturn(Collections.emptyList()).when(mAudioManager).getActivePlaybackConfigurations();
            return;
        }

        AudioAttributes attributes =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build();
        AudioPlaybackConfiguration activeVoiceConfig =
                Mockito.mock(AudioPlaybackConfiguration.class);
        doReturn(attributes).when(activeVoiceConfig).getAudioAttributes();
        doReturn(true).when(activeVoiceConfig).isActive();
        doReturn(List.of(activeVoiceConfig)).when(mAudioManager).getActivePlaybackConfigurations();
    }
}
