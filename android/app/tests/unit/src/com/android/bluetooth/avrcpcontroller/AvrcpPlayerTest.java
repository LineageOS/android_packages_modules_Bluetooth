/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link AvrcpPlayer}. */
@RunWith(AndroidJUnit4.class)
public class AvrcpPlayerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private PlayerApplicationSettings mPlayerApplicationSettings;

    private static final String TEST_NAME = "test_name";
    private static final int TEST_PLAYER_ID = 1;
    private static final int TEST_FEATURE = AvrcpPlayer.FEATURE_PLAY;
    private static final int TEST_PLAY_STATUS = PlaybackStateCompat.STATE_STOPPED;
    private static final int TEST_PLAY_TIME = 1;

    private final AvrcpItem mAvrcpItem = new AvrcpItem.Builder().build();
    private final BluetoothDevice mDevice = getTestDevice(45);

    @Test
    public void buildAvrcpPlayer() {
        AvrcpPlayer.Builder builder = new AvrcpPlayer.Builder();
        builder.setDevice(mDevice);
        builder.setPlayerId(TEST_PLAYER_ID);
        builder.setName(TEST_NAME);
        builder.setSupportedFeature(TEST_FEATURE);
        builder.setPlayStatus(TEST_PLAY_STATUS);
        builder.setCurrentTrack(mAvrcpItem);

        AvrcpPlayer avrcpPlayer = builder.build();

        assertThat(avrcpPlayer.getDevice()).isEqualTo(mDevice);
        assertThat(avrcpPlayer.getId()).isEqualTo(TEST_PLAYER_ID);
        assertThat(avrcpPlayer.getName()).isEqualTo(TEST_NAME);
        assertThat(avrcpPlayer.supportsFeature(TEST_FEATURE)).isTrue();
        assertThat(avrcpPlayer.getPlayStatus()).isEqualTo(TEST_PLAY_STATUS);
        assertThat(avrcpPlayer.getCurrentTrack()).isEqualTo(mAvrcpItem);
        assertThat(avrcpPlayer.getPlaybackState().getActions())
                .isEqualTo(PlaybackStateCompat.ACTION_PREPARE | PlaybackStateCompat.ACTION_PLAY);
    }

    @Test
    public void setAndGetPlayTime() {
        AvrcpPlayer avrcpPlayer = new AvrcpPlayer.Builder().build();

        avrcpPlayer.setPlayTime(TEST_PLAY_TIME);

        assertThat(avrcpPlayer.getPlayTime()).isEqualTo(TEST_PLAY_TIME);
    }

    @Test
    public void setPlayStatus() {
        AvrcpPlayer avrcpPlayer = new AvrcpPlayer.Builder().build();
        avrcpPlayer.setPlayTime(TEST_PLAY_TIME);

        avrcpPlayer.setPlayStatus(PlaybackStateCompat.STATE_PLAYING);
        assertThat(avrcpPlayer.getPlaybackState().getPlaybackSpeed()).isEqualTo(1);

        avrcpPlayer.setPlayStatus(PlaybackStateCompat.STATE_PAUSED);
        assertThat(avrcpPlayer.getPlaybackState().getPlaybackSpeed()).isEqualTo(0);

        avrcpPlayer.setPlayStatus(PlaybackStateCompat.STATE_FAST_FORWARDING);
        assertThat(avrcpPlayer.getPlaybackState().getPlaybackSpeed()).isEqualTo(3);

        avrcpPlayer.setPlayStatus(PlaybackStateCompat.STATE_REWINDING);
        assertThat(avrcpPlayer.getPlaybackState().getPlaybackSpeed()).isEqualTo(-3);
    }

    @Test
    public void setSupportedPlayerApplicationSettings() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(PlayerApplicationSettings.REPEAT_STATUS);
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(PlayerApplicationSettings.SHUFFLE_STATUS);
        AvrcpPlayer avrcpPlayer = new AvrcpPlayer.Builder().build();
        long expectedActions =
                PlaybackStateCompat.ACTION_PREPARE
                        | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                        | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;

        avrcpPlayer.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(avrcpPlayer.getPlaybackState().getActions()).isEqualTo(expectedActions);
    }

    @Test
    public void supportsSetting() {
        int settingType = 1;
        int settingValue = 1;
        doReturn(true).when(mPlayerApplicationSettings).supportsSetting(settingType, settingValue);
        AvrcpPlayer avrcpPlayer = new AvrcpPlayer.Builder().build();

        avrcpPlayer.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(avrcpPlayer.supportsSetting(settingType, settingValue)).isTrue();
    }

    @Test
    public void updateAvailableActions() {
        byte[] supportedFeatures = new byte[16];
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_STOP);
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_PAUSE);
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_REWIND);
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_FAST_FORWARD);
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_FORWARD);
        setSupportedFeature(supportedFeatures, AvrcpPlayer.FEATURE_PREVIOUS);
        long expectedActions =
                PlaybackStateCompat.ACTION_PREPARE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_REWIND
                        | PlaybackStateCompat.ACTION_FAST_FORWARD
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        AvrcpPlayer avrcpPlayer =
                new AvrcpPlayer.Builder().setSupportedFeatures(supportedFeatures).build();

        assertThat(avrcpPlayer.getPlaybackState().getActions()).isEqualTo(expectedActions);
    }

    @Test
    public void getShuffleMode_shuffleSet_returnsSetValue() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(PlayerApplicationSettings.SHUFFLE_STATUS);
        doReturn(PlaybackStateCompat.SHUFFLE_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.SHUFFLE_STATUS);

        AvrcpPlayer player = new AvrcpPlayer.Builder().build();
        player.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);
        player.setCurrentPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(player.getShuffleMode()).isEqualTo(PlaybackStateCompat.SHUFFLE_MODE_ALL);
    }

    @Test
    public void getShuffleMode_shuffleUnset_returnsNone() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(eq(PlayerApplicationSettings.SHUFFLE_STATUS));
        doReturn(/* jni_invalid */ -1)
                .when(mPlayerApplicationSettings)
                .getSetting(eq(PlayerApplicationSettings.SHUFFLE_STATUS));

        AvrcpPlayer player = new AvrcpPlayer.Builder().build();
        player.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);
        player.setCurrentPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(player.getShuffleMode()).isEqualTo(PlaybackStateCompat.SHUFFLE_MODE_NONE);
    }

    @Test
    public void getRepeatMode_repeatSet_returnsSetValue() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(PlayerApplicationSettings.REPEAT_STATUS);
        doReturn(PlaybackStateCompat.REPEAT_MODE_ALL)
                .when(mPlayerApplicationSettings)
                .getSetting(PlayerApplicationSettings.REPEAT_STATUS);

        AvrcpPlayer player = new AvrcpPlayer.Builder().build();
        player.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);
        player.setCurrentPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(player.getRepeatMode()).isEqualTo(PlaybackStateCompat.REPEAT_MODE_ALL);
    }

    @Test
    public void getRepeatMode_repeatUnset_returnsNone() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(eq(PlayerApplicationSettings.REPEAT_STATUS));
        doReturn(/* jni_invalid */ -1)
                .when(mPlayerApplicationSettings)
                .getSetting(eq(PlayerApplicationSettings.REPEAT_STATUS));

        AvrcpPlayer player = new AvrcpPlayer.Builder().build();
        player.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);
        player.setCurrentPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(player.getRepeatMode()).isEqualTo(PlaybackStateCompat.REPEAT_MODE_NONE);
    }

    @Test
    public void getShuffleAndRepeatMode_playerSettingsUnset_returnsNone() {
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(eq(PlayerApplicationSettings.SHUFFLE_STATUS));
        doReturn(true)
                .when(mPlayerApplicationSettings)
                .supportsSetting(eq(PlayerApplicationSettings.REPEAT_STATUS));

        AvrcpPlayer player = new AvrcpPlayer.Builder().build();
        player.setSupportedPlayerApplicationSettings(mPlayerApplicationSettings);

        assertThat(player.getShuffleMode()).isEqualTo(PlaybackStateCompat.SHUFFLE_MODE_NONE);
        assertThat(player.getRepeatMode()).isEqualTo(PlaybackStateCompat.REPEAT_MODE_NONE);
    }

    @Test
    public void toString_returnsInfo() {
        AvrcpPlayer avrcpPlayer =
                new AvrcpPlayer.Builder()
                        .setPlayerId(TEST_PLAYER_ID)
                        .setName(TEST_NAME)
                        .setCurrentTrack(mAvrcpItem)
                        .build();

        assertThat(avrcpPlayer.toString()).isNotNull();
    }

    @Test
    public void notifyImageDownload() {
        String uuid = "1111";
        Uri uri = Uri.parse("http://test.com");
        AvrcpItem trackWithDifferentUuid = new AvrcpItem.Builder().build();
        AvrcpItem trackWithSameUuid = new AvrcpItem.Builder().build();
        trackWithSameUuid.setCoverArtUuid(uuid);
        AvrcpPlayer avrcpPlayer = new AvrcpPlayer.Builder().build();

        assertThat(avrcpPlayer.notifyImageDownload(uuid, uri)).isFalse();

        avrcpPlayer.updateCurrentTrack(trackWithDifferentUuid);
        assertThat(avrcpPlayer.notifyImageDownload(uuid, uri)).isFalse();

        avrcpPlayer.updateCurrentTrack(trackWithSameUuid);
        assertThat(avrcpPlayer.notifyImageDownload(uuid, uri)).isTrue();
    }

    private static void setSupportedFeature(byte[] supportedFeatures, int feature) {
        int byteNumber = feature / 8;
        byte bitMask = (byte) (1 << (feature % 8));
        supportedFeatures[byteNumber] = (byte) (supportedFeatures[byteNumber] | bitMask);
    }
}
