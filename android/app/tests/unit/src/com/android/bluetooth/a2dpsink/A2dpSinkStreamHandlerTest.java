/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.a2dpsink;

import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerNativeInterface;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Optional;

/** Test cases for {@link A2dpSinkStreamHandler}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class A2dpSinkStreamHandlerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Mock private A2dpSinkNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mPackageManager;

    private static final int DUCK_PERCENT = 75;

    private HandlerThread mHandlerThread;
    private A2dpSinkStreamHandler mStreamHandler;

    @Before
    public void setUp() throws Exception {
        doReturn(DUCK_PERCENT).when(mResources).getInteger(anyInt());

        doReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                .when(mAudioManager)
                .requestAudioFocus(any());
        doReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                .when(mAudioManager)
                .abandonAudioFocus(any());

        final var context = InstrumentationRegistry.getInstrumentation().getContext();
        doReturn(context.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        doReturn(mResources).when(mAdapterService).getResources();
        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);

        final var mAvrcpControllerNativeInterface = mock(AvrcpControllerNativeInterface.class);
        final var avrcpControllerService =
                new AvrcpControllerService(mAdapterService, mAvrcpControllerNativeInterface);
        doReturn(Optional.of(avrcpControllerService))
                .when(mAdapterService)
                .getAvrcpControllerService();

        // Mock the looper
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);

        mHandlerThread = new HandlerThread("A2dpSinkStreamHandlerTest");
        mHandlerThread.start();
        doReturn(mHandlerThread.getLooper()).when(mAdapterService).getMainLooper();

        mStreamHandler = spy(new A2dpSinkStreamHandler(mAdapterService, mNativeInterface));
    }

    @Test
    public void testSrcStart() {
        // Stream started without local play, expect no change in streaming.
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_STR_START));
        verify(mAudioManager, never()).requestAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(1);
        verify(mNativeInterface, never()).informAudioTrackGain(1.0f);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testSrcStop() {
        // Stream stopped without local play, expect no change in streaming.
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_STR_STOP));
        verify(mAudioManager, never()).requestAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(1);
        verify(mNativeInterface, never()).informAudioTrackGain(1.0f);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testSnkPlay() {
        // Play was pressed locally, expect streaming to start soon.
        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SNK_PLAY));
        verify(mAudioManager).requestAudioFocus(any());
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testSnkPause() {
        // Pause was pressed locally, expect streaming to stop.
        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SNK_PAUSE));
        verify(mAudioManager, never()).requestAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(1);
        verify(mNativeInterface, never()).informAudioTrackGain(1.0f);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testDisconnect() {
        // Remote device was disconnected, expect streaming to stop.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(A2dpSinkStreamHandler.DISCONNECT));
        verify(mAudioManager, never()).abandonAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(0);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testSrcPlay() {
        // Play was pressed remotely, expect no streaming due to lack of audio focus.
        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_PLAY));
        verify(mAudioManager, never()).requestAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(1);
        verify(mNativeInterface, never()).informAudioTrackGain(1.0f);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testSrcPlayIot() {
        // Play was pressed remotely for an iot device, expect streaming to start.
        doReturn(true).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_EMBEDDED);
        // Ensure other conditions for requesting focus are false
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        doReturn(false).when(mResources).getBoolean(anyInt());
        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_PLAY));

        verify(mAudioManager).requestAudioFocus(any());
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.isPlaying()).isTrue();
    }

    @Test
    public void testSrcPlay_onTvDevice_requestsFocus() {
        // A remote PLAY command on a TV device should request audio focus and start streaming.
        doReturn(true).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        // Ensure other conditions for requesting focus are false
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_EMBEDDED);
        doReturn(false).when(mResources).getBoolean(anyInt());

        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_PLAY));

        // Verify that audio focus is requested.
        verify(mAudioManager).requestAudioFocus(any());
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.isPlaying()).isTrue();
    }

    @Test
    public void testSrcPlay_withFocusRequestEnabled_requestsFocus() {
        // A remote PLAY command should request focus when the auto-request config is enabled.
        doReturn(true)
                .when(mResources)
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
        // Ensure other conditions for requesting focus are false
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_EMBEDDED);

        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_PLAY));

        // Verify that audio focus is requested.
        verify(mAudioManager).requestAudioFocus(any());
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.isPlaying()).isTrue();
    }

    @Test
    public void testSrcPause() {
        // Play was pressed locally, expect streaming to start.
        mStreamHandler.handleMessage(mStreamHandler.obtainMessage(A2dpSinkStreamHandler.SRC_PLAY));
        verify(mAudioManager, never()).requestAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(1);
        verify(mNativeInterface, never()).informAudioTrackGain(1.0f);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }

    @Test
    public void testFocusGain() {
        // Focus was gained, expect streaming to resume.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE, AudioManager.AUDIOFOCUS_GAIN));
        verify(mAudioManager).requestAudioFocus(any());
        verify(mNativeInterface).informAudioFocusState(1);
        verify(mNativeInterface).informAudioTrackGain(1.0f);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.getFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
    }

    @Test
    public void testFocusTransientMayDuck() {
        // TransientMayDuck focus was gained, expect audio stream to duck.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));
        verify(mNativeInterface).informAudioTrackGain(DUCK_PERCENT / 100.0f);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.getFocusState())
                .isEqualTo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    }

    @Test
    public void testFocusLostTransient() {
        // Focus was lost transiently, expect streaming to stop.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));
        verify(mAudioManager, never()).abandonAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(0);
        verify(mNativeInterface).informAudioTrackGain(0);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.getFocusState())
                .isEqualTo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    }

    @Test
    public void testFocusRerequest() {
        // Focus was lost transiently, expect streaming to stop.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));
        verify(mAudioManager, never()).abandonAudioFocus(any());
        verify(mNativeInterface, never()).informAudioFocusState(0);
        verify(mNativeInterface).informAudioTrackGain(0);
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(A2dpSinkStreamHandler.REQUEST_FOCUS, true));
        verify(mAudioManager, times(2)).requestAudioFocus(any());
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
    }

    @Test
    public void testFocusGainFromTransientLoss() {
        // Focus was lost transiently and then regained.
        testFocusLostTransient();

        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE, AudioManager.AUDIOFOCUS_GAIN));
        verify(mAudioManager, never()).abandonAudioFocus(any());
        verify(mNativeInterface).informAudioTrackGain(1.0f);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.getFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
    }

    @Test
    public void testFocusLost() {
        // Focus was lost permanently, expect streaming to stop.
        testSnkPlay();
        mStreamHandler.handleMessage(
                mStreamHandler.obtainMessage(
                        A2dpSinkStreamHandler.AUDIO_FOCUS_CHANGE, AudioManager.AUDIOFOCUS_LOSS));
        verify(mAudioManager).abandonAudioFocus(any());
        verify(mNativeInterface).informAudioFocusState(0);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStreamHandler.getFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE);
        assertThat(mStreamHandler.isPlaying()).isFalse();
    }
}
