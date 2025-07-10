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

package com.android.bluetooth.avrcpcontroller;

import android.support.v4.media.session.PlaybackStateCompat;

/** A package global set of utilities for the AVRCP Controller implementation to leverage */
public final class AvrcpControllerUtils {
    public static final String TAG_PREFIX_AVRCP = "Avrcp";
    public static final String TAG_PREFIX_AVRCP_CONTROLLER = TAG_PREFIX_AVRCP + "Controller.";

    private AvrcpControllerUtils() {}

    /** Convert an AVRCP Passthrough command id to a human readable version of the key */
    public static String passThruIdToString(int id) {
        return switch (id) {
                    case AvrcpControllerService.PASS_THRU_CMD_ID_PLAY -> "PLAY";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE -> "PAUSE";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_VOL_UP -> "VOL_UP";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_VOL_DOWN -> "VOL_DOWN";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_STOP -> "STOP";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_FF -> "FF";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_REWIND -> "REWIND";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD -> "FORWARD";
                    case AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD -> "BACKWARD";
                    default -> "UNKNOWN_CMD";
                }
                + " ("
                + id
                + ")";
    }

    /** Convert an entire PlaybackStateCompat to a string that contains human readable states */
    public static String playbackStateCompatToString(PlaybackStateCompat playbackState) {
        if (playbackState == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder("PlaybackState {");
        sb.append("state=").append(playbackStateToString(playbackState.getState()));
        sb.append(", position=").append(playbackState.getPosition());
        sb.append(", buffered position=").append(playbackState.getBufferedPosition());
        sb.append(", speed=").append(playbackState.getPlaybackSpeed());
        sb.append(", updated=").append(playbackState.getLastPositionUpdateTime());
        sb.append(", actions=").append(playbackState.getActions());
        sb.append(", error code=").append(playbackState.getErrorCode());
        sb.append(", error message=").append(playbackState.getErrorMessage());
        sb.append(", custom actions=").append(playbackState.getCustomActions());
        sb.append(", active item id=").append(playbackState.getActiveQueueItemId());
        sb.append("}");
        return sb.toString();
    }

    /** Convert a playback state constant to a human readable version of the state */
    public static String playbackStateToString(int playbackState) {
        return switch (playbackState) {
                    case PlaybackStateCompat.STATE_NONE -> "STATE_NONE";
                    case PlaybackStateCompat.STATE_STOPPED -> "STATE_STOPPED";
                    case PlaybackStateCompat.STATE_PAUSED -> "STATE_PAUSED";
                    case PlaybackStateCompat.STATE_PLAYING -> "STATE_PLAYING";
                    case PlaybackStateCompat.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING";
                    case PlaybackStateCompat.STATE_REWINDING -> "STATE_REWINDING";
                    case PlaybackStateCompat.STATE_BUFFERING -> "STATE_BUFFERING";
                    case PlaybackStateCompat.STATE_ERROR -> "STATE_ERROR";
                    case PlaybackStateCompat.STATE_CONNECTING -> "STATE_CONNECTING";
                    case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS ->
                            "STATE_SKIPPING_TO_PREVIOUS";
                    case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT";
                    case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM ->
                            "STATE_SKIPPING_TO_QUEUE_ITEM";
                    default -> "UNKNOWN_PLAYBACK_STATE";
                }
                + " ("
                + playbackState
                + ")";
    }
}
