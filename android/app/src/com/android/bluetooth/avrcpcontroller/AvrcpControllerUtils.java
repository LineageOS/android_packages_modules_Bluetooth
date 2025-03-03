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

    /** Convert an AVRCP Passthrough command id to a human readable version of the key */
    public static String passThruIdToString(int id) {
        StringBuilder sb = new StringBuilder();
        switch (id) {
            case AvrcpControllerService.PASS_THRU_CMD_ID_PLAY:
                sb.append("PLAY");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE:
                sb.append("PAUSE");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_VOL_UP:
                sb.append("VOL_UP");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_VOL_DOWN:
                sb.append("VOL_DOWN");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_STOP:
                sb.append("STOP");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_FF:
                sb.append("FF");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_REWIND:
                sb.append("REWIND");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD:
                sb.append("FORWARD");
                break;
            case AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD:
                sb.append("BACKWARD");
                break;
            default:
                sb.append("UNKNOWN_CMD_").append(id);
                break;
        }
        sb.append(" (").append(id).append(")");
        return sb.toString();
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
        StringBuilder sb = new StringBuilder();
        switch (playbackState) {
            case PlaybackStateCompat.STATE_NONE:
                sb.append("STATE_NONE");
                break;
            case PlaybackStateCompat.STATE_STOPPED:
                sb.append("STATE_STOPPED");
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                sb.append("STATE_PAUSED");
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                sb.append("STATE_PLAYING");
                break;
            case PlaybackStateCompat.STATE_FAST_FORWARDING:
                sb.append("STATE_FAST_FORWARDING");
                break;
            case PlaybackStateCompat.STATE_REWINDING:
                sb.append("STATE_REWINDING");
                break;
            case PlaybackStateCompat.STATE_BUFFERING:
                sb.append("STATE_BUFFERING");
                break;
            case PlaybackStateCompat.STATE_ERROR:
                sb.append("STATE_ERROR");
                break;
            case PlaybackStateCompat.STATE_CONNECTING:
                sb.append("STATE_CONNECTING");
                break;
            case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                sb.append("STATE_SKIPPING_TO_PREVIOUS");
                break;
            case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                sb.append("STATE_SKIPPING_TO_NEXT");
                break;
            case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                sb.append("STATE_SKIPPING_TO_QUEUE_ITEM");
                break;
            default:
                sb.append("UNKNOWN_PLAYBACK_STATE");
                break;
        }
        sb.append(" (").append(playbackState).append(")");
        return sb.toString();
    }
}
