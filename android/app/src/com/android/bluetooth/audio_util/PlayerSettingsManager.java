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

import static android.os.Process.INVALID_UID;

import android.app.ActivityManager;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.android.bluetooth.avrcp.AvrcpTargetService;

import java.util.HashMap;
import java.util.Map;

/** Manager class for player apps. */
public class PlayerSettingsManager {
    private static final String TAG = PlayerSettingsManager.class.getSimpleName();

    private final MediaPlayerList mMediaPlayerList;
    private final AvrcpTargetService mService;

    private MediaControllerCompat mActivePlayerController = null;
    private final MediaControllerCallback mControllerCallback;

    private final ActivityManager mActivityManager;

    private int mActiveSessionUid = INVALID_UID;

    /**
     * Map containing the current values of the player settings. Used to prevent sending a state
     * change event when values are unchanged.
     */
    private final Map<Integer, Integer> mCurrentAppSettingValue = new HashMap<>();

    /**
     * Instantiates a new PlayerSettingsManager.
     *
     * @param mediaPlayerList is used to retrieve the current active player.
     */
    public PlayerSettingsManager(MediaPlayerList mediaPlayerList, AvrcpTargetService service) {
        mService = service;
        mMediaPlayerList = mediaPlayerList;
        mMediaPlayerList.setPlayerSettingsCallback(this::activePlayerChanged);
        mControllerCallback = new MediaControllerCallback();
        mActivityManager = mService.getSystemService(ActivityManager.class);

        mCurrentAppSettingValue.put(
                PlayerSettingsValues.SETTING_REPEAT, PlayerSettingsValues.STATE_REPEAT_OFF);
        mCurrentAppSettingValue.put(
                PlayerSettingsValues.SETTING_SHUFFLE, PlayerSettingsValues.STATE_SHUFFLE_OFF);

        activePlayerChanged(mMediaPlayerList.getActivePlayer());
    }

    /** Unregister callbacks */
    public void cleanup() {
        if (mActivePlayerController != null) {
            unregisterMediaControllerCallback(mActivePlayerController, mControllerCallback);
        }
        mActivePlayerController = null;
        mActiveSessionUid = INVALID_UID;
        mCurrentAppSettingValue.clear();
    }

    /** Updates the active player controller. */
    private void activePlayerChanged(MediaPlayerWrapper mediaPlayerWrapper) {
        if (mActivePlayerController != null) {
            boolean packageIsNotFrozen = appIsNotFrozen();
            Log.i(
                    TAG,
                    "activePlayerChanged - current active app uid="
                            + mActiveSessionUid
                            + " isNotFrozen="
                            + packageIsNotFrozen);
            // If the package is frozen, the binder call to unregister the callback will crash, and
            // ActivityManager will kill the Media Player app.
            // If mediaPlayerWrapper is null, this means no more controller is available, so we
            // can't unregister the callback.
            if (packageIsNotFrozen && mediaPlayerWrapper != null) {
                Log.i(TAG, "activePlayerChanged - unregistering the MediaControllerCallback");
                unregisterMediaControllerCallback(mActivePlayerController, mControllerCallback);
            }
        }
        if (mediaPlayerWrapper != null && mediaPlayerWrapper.getSessionToken() != null) {
            mActiveSessionUid = mediaPlayerWrapper.getSessionToken().getUid();
            MediaSessionCompat.Token sessionToken =
                    MediaSessionCompat.Token.fromToken(mediaPlayerWrapper.getSessionToken());
            if (sessionToken == null) {
                Log.w(TAG, "activePlayerChanged - sessionToken is null");
                return;
            }
            Log.i(TAG, "activePlayerChanged to " + mediaPlayerWrapper.getPackageName());
            mActivePlayerController = new MediaControllerCompat(mService, sessionToken);
            if (!registerMediaControllerCallback(mActivePlayerController, mControllerCallback)) {
                Log.e(TAG, "activePlayerChanged - Couldn't register callback");
                mActivePlayerController = null;
                mActiveSessionUid = INVALID_UID;
            }
        } else {
            Log.i(TAG, "activePlayerChanged - New player is null, removed active player");
            mActivePlayerController = null;
            mActiveSessionUid = INVALID_UID;
        }
        updateRemoteDevice();
    }

    /**
     * Sends the MediaController values of the active player to the remote device.
     *
     * <p>This is called when: - The class is created and the session is ready - The class is
     * destroyed - The active player changed and the session is ready - The last active player has
     * been removed - The repeat / shuffle player state changed
     */
    private void updateRemoteDevice() {
        int currentRepeatMode =
                mCurrentAppSettingValue.getOrDefault(
                        PlayerSettingsValues.SETTING_REPEAT, PlayerSettingsValues.STATE_REPEAT_OFF);
        int currentShuffleMode =
                mCurrentAppSettingValue.getOrDefault(
                        PlayerSettingsValues.SETTING_SHUFFLE,
                        PlayerSettingsValues.STATE_SHUFFLE_OFF);

        int repeatMode = getPlayerRepeatMode();
        int shuffleMode = getPlayerShuffleMode();

        Log.i(
                TAG,
                "updateRemoteDevice: repeat ("
                        + getRepeatModeStringValue(currentRepeatMode)
                        + " -> "
                        + getRepeatModeStringValue(repeatMode)
                        + "), shuffle ("
                        + getShuffleModeStringValue(currentShuffleMode)
                        + " -> "
                        + getShuffleModeStringValue(shuffleMode)
                        + ")");

        if (currentRepeatMode != repeatMode || currentShuffleMode != shuffleMode) {
            mCurrentAppSettingValue.put(PlayerSettingsValues.SETTING_REPEAT, repeatMode);
            mCurrentAppSettingValue.put(PlayerSettingsValues.SETTING_SHUFFLE, shuffleMode);
            mService.sendPlayerSettings(repeatMode, shuffleMode);
        }
    }

    /** Called from remote device to set the active player repeat mode. */
    public boolean setPlayerRepeatMode(int repeatMode) {
        if (mActivePlayerController == null) {
            Log.i(TAG, "setPlayerRepeatMode - no active player");
            return false;
        }
        Log.d(TAG, "setPlayerRepeatMode repeat=" + repeatMode);

        MediaControllerCompat.TransportControls controls;
        try {
            controls = mActivePlayerController.getTransportControls();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
        switch (repeatMode) {
            case PlayerSettingsValues.STATE_REPEAT_OFF ->
                    controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
            case PlayerSettingsValues.STATE_REPEAT_SINGLE_TRACK ->
                    controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ONE);
            case PlayerSettingsValues.STATE_REPEAT_GROUP ->
                    controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_GROUP);
            case PlayerSettingsValues.STATE_REPEAT_ALL_TRACK ->
                    controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ALL);
            default -> {
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
                return false;
            }
        }
        return true;
    }

    /** Called from remote device to set the active player shuffle mode. */
    public boolean setPlayerShuffleMode(int shuffleMode) {
        if (mActivePlayerController == null) {
            Log.i(TAG, "setPlayerShuffleMode - no active player");
            return false;
        }
        Log.d(TAG, "setPlayerShuffleMode shuffle=" + shuffleMode);

        MediaControllerCompat.TransportControls controls;
        try {
            controls = mActivePlayerController.getTransportControls();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
        switch (shuffleMode) {
            case PlayerSettingsValues.STATE_SHUFFLE_OFF ->
                    controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
            case PlayerSettingsValues.STATE_SHUFFLE_GROUP ->
                    controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_GROUP);
            case PlayerSettingsValues.STATE_SHUFFLE_ALL_TRACK ->
                    controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL);
            default -> {
                controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
                return false;
            }
        }
        return true;
    }

    /**
     * Retrieves & converts the repeat value of the active player MediaController to AVRCP values
     */
    public int getPlayerRepeatMode() {
        if (mActivePlayerController == null) {
            Log.i(TAG, "getPlayerRepeatMode - no active player");
            return PlayerSettingsValues.STATE_REPEAT_OFF;
        }
        int mediaFwkMode = PlaybackStateCompat.REPEAT_MODE_NONE;
        // If the app is frozen, the binder call will fail and the app will be killed.
        if (appIsNotFrozen()) {
            try {
                mediaFwkMode = mActivePlayerController.getRepeatMode();
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
        return switch (mediaFwkMode) {
            case PlaybackStateCompat.REPEAT_MODE_NONE -> PlayerSettingsValues.STATE_REPEAT_OFF;
            case PlaybackStateCompat.REPEAT_MODE_ONE ->
                    PlayerSettingsValues.STATE_REPEAT_SINGLE_TRACK;
            case PlaybackStateCompat.REPEAT_MODE_GROUP -> PlayerSettingsValues.STATE_REPEAT_GROUP;
            case PlaybackStateCompat.REPEAT_MODE_ALL -> PlayerSettingsValues.STATE_REPEAT_ALL_TRACK;
            case PlaybackStateCompat.REPEAT_MODE_INVALID -> PlayerSettingsValues.STATE_REPEAT_OFF;
            default -> PlayerSettingsValues.STATE_REPEAT_OFF;
        };
    }

    /**
     * Retrieves & converts the shuffle value of the active player MediaController to AVRCP values
     */
    public int getPlayerShuffleMode() {
        if (mActivePlayerController == null) {
            Log.i(TAG, "getPlayerShuffleMode - no active player");
            return PlayerSettingsValues.STATE_SHUFFLE_OFF;
        }
        int mediaFwkMode = PlaybackStateCompat.SHUFFLE_MODE_NONE;
        // If the app is frozen, the binder call will fail and the app will be killed.
        if (appIsNotFrozen()) {
            try {
                mediaFwkMode = mActivePlayerController.getShuffleMode();
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
        return switch (mediaFwkMode) {
            case PlaybackStateCompat.SHUFFLE_MODE_NONE -> PlayerSettingsValues.STATE_SHUFFLE_OFF;
            case PlaybackStateCompat.SHUFFLE_MODE_GROUP -> PlayerSettingsValues.STATE_SHUFFLE_GROUP;
            case PlaybackStateCompat.SHUFFLE_MODE_ALL ->
                    PlayerSettingsValues.STATE_SHUFFLE_ALL_TRACK;
            case PlaybackStateCompat.SHUFFLE_MODE_INVALID -> PlayerSettingsValues.STATE_SHUFFLE_OFF;
            default -> PlayerSettingsValues.STATE_SHUFFLE_OFF;
        };
    }

    /**
     * The binder of some MediaControllers can fail to register the callback, this result on a crash
     * on the media side that has to be handled here.
     */
    private static boolean registerMediaControllerCallback(
            MediaControllerCompat controller, MediaControllerCallback callback) {
        try {
            controller.registerCallback(callback);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
        return true;
    }

    /**
     * The binder of some MediaControllers can fail to unregister the callback, this result on a
     * crash on the media side that has to be handled here.
     */
    private static boolean unregisterMediaControllerCallback(
            MediaControllerCompat controller, MediaControllerCallback callback) {
        try {
            controller.unregisterCallback(callback);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
        return true;
    }

    // Receives callbacks from the MediaControllerCompat.
    private class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onRepeatModeChanged(final int repeatMode) {
            Log.d(TAG, "onRepeatModeChanged repeatMode=" + repeatMode);
            updateRemoteDevice();
        }

        @Override
        public void onSessionReady() {
            Log.d(TAG, "onSessionReady");
            updateRemoteDevice();
        }

        @Override
        public void onShuffleModeChanged(final int shuffleMode) {
            Log.d(TAG, "onShuffleModeChanged shuffleMode=" + shuffleMode);
            updateRemoteDevice();
        }
    }

    /** Class containing all the Shuffle/Repeat values as defined in the BT spec. */
    public static final class PlayerSettingsValues {
        /** Repeat setting, as defined by Bluetooth specification. */
        public static final int SETTING_REPEAT = 2;

        /** Shuffle setting, as defined by Bluetooth specification. */
        public static final int SETTING_SHUFFLE = 3;

        /** Repeat OFF state, as defined by Bluetooth specification. */
        public static final int STATE_REPEAT_OFF = 1;

        /** Single track repeat, as defined by Bluetooth specification. */
        public static final int STATE_REPEAT_SINGLE_TRACK = 2;

        /** All track repeat, as defined by Bluetooth specification. */
        public static final int STATE_REPEAT_ALL_TRACK = 3;

        /** Group repeat, as defined by Bluetooth specification. */
        public static final int STATE_REPEAT_GROUP = 4;

        /** Shuffle OFF state, as defined by Bluetooth specification. */
        public static final int STATE_SHUFFLE_OFF = 1;

        /** All track shuffle, as defined by Bluetooth specification. */
        public static final int STATE_SHUFFLE_ALL_TRACK = 2;

        /** Group shuffle, as defined by Bluetooth specification. */
        public static final int STATE_SHUFFLE_GROUP = 3;

        /** Default state off. */
        public static final int STATE_DEFAULT_OFF = 1;
    }

    private static String getRepeatModeStringValue(int repeatMode) {
        return switch (repeatMode) {
            case PlayerSettingsValues.STATE_REPEAT_OFF -> "STATE_REPEAT_OFF";
            case PlayerSettingsValues.STATE_REPEAT_SINGLE_TRACK -> "STATE_REPEAT_SINGLE_TRACK";
            case PlayerSettingsValues.STATE_REPEAT_ALL_TRACK -> "STATE_REPEAT_ALL_TRACK";
            case PlayerSettingsValues.STATE_REPEAT_GROUP -> "STATE_REPEAT_GROUP";
            default -> "STATE_DEFAULT_OFF";
        };
    }

    private static String getShuffleModeStringValue(int shuffleMode) {
        return switch (shuffleMode) {
            case PlayerSettingsValues.STATE_SHUFFLE_OFF -> "STATE_SHUFFLE_OFF";
            case PlayerSettingsValues.STATE_SHUFFLE_ALL_TRACK -> "STATE_SHUFFLE_ALL_TRACK";
            case PlayerSettingsValues.STATE_SHUFFLE_GROUP -> "STATE_SHUFFLE_GROUP";
            default -> "STATE_DEFAULT_OFF";
        };
    }

    private boolean appIsNotFrozen() {
        if (mActiveSessionUid == INVALID_UID) {
            // This should never happen as this function is called only when there is an active
            // controller, which would have also set the session UID.
            Log.e(TAG, "appIsNotFrozen - mActiveSessionUid is not set, defaulting to true.");
            return true;
        }
        return mActivityManager.getUidFrozenState(new int[] {mActiveSessionUid})[0]
                == ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN;
    }
}
