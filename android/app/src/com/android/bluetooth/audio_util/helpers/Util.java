/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

class Util {
    private static final String TAG = "audio_util." + Util.class.getSimpleName();

    private static final String VFS_COVER_ART_ENABLED_PROPERTY =
            "bluetooth.profile.avrcp.target.vfs_coverart.enabled";

    private static final String MULTIPLE_PLAYERS_SUPPORT_ENABLED_PROPERTY =
            "bluetooth.profile.avrcp.target.multiple_players.enabled";

    // See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom
    @VisibleForTesting
    static class UriImagesSupport {
        static boolean sValue = SystemProperties.getBoolean(VFS_COVER_ART_ENABLED_PROPERTY, false);
    }

    private static class MultiPlayersSupport {
        private static boolean sValue =
                SystemProperties.getBoolean(MULTIPLE_PLAYERS_SUPPORT_ENABLED_PROPERTY, false);
    }

    // TODO (apanicke): Remove this prefix later, for now it makes debugging easier.
    public static final String NOW_PLAYING_PREFIX = "NowPlayingId";

    private Util() {}

    /** Get an empty set of Metadata */
    public static final Metadata empty_data() {
        Metadata.Builder builder = new Metadata.Builder();
        return builder.useDefaults().build();
    }

    /** Determine if a set of Metadata is "empty" as defined by audio_util. */
    public static final boolean isEmptyData(Metadata data) {
        if (data == null) return true;
        // Note: We need both equals() and an explicit media id check because equals() does
        // not check for the media ID.
        return (empty_data().equals(data) && data.mediaId.equals(Metadata.EMPTY_MEDIA_ID));
    }

    /**
     * Get whether or not Bluetooth is configured to support URI images.
     *
     * <p>Note that creating URI images will dramatically increase memory usage.
     */
    public static boolean areUriImagesSupported() {
        return UriImagesSupport.sValue;
    }

    /**
     * Get whether or not Bluetooth is configured to advertise multiple media players.
     *
     * <p>This is disabled by default as some car head units will stop working if multiple media
     * players are present. Addressed Player and Browsing commands should always display only one
     * media player to the remote device by default.
     */
    public static boolean areMultiplePlayersSupported() {
        return MultiPlayersSupport.sValue;
    }

    /** Translate a MediaItem to audio_util's Metadata */
    public static Metadata toMetadata(Context context, MediaItem item) {
        Metadata.Builder builder = new Metadata.Builder();
        try {
            return builder.useContext(context).useDefaults().fromMediaItem(item).build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build Metadata from MediaItem, returning empty data", e);
            return empty_data();
        }
    }

    /** Translate a MediaSession.QueueItem to audio_util's Metadata */
    public static Metadata toMetadata(Context context, MediaSession.QueueItem item) {
        Metadata.Builder builder = new Metadata.Builder();

        try {
            builder.useDefaults().fromQueueItem(item);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build Metadata from QueueItem, returning empty data", e);
            return empty_data();
        }

        // For Queue Items, the Media Id will always be just its Queue ID
        // We don't need to use its actual ID since we don't promise UIDS being valid
        // between a file system and it's now playing list.
        if (item != null) builder.setMediaId(NOW_PLAYING_PREFIX + item.getQueueId());
        return builder.build();
    }

    /** Translate a MediaMetadata to audio_util's Metadata */
    public static Metadata toMetadata(Context context, MediaMetadata data) {
        Metadata.Builder builder = new Metadata.Builder();
        // This will always be currsong. The AVRCP service will overwrite the mediaId if it needs to
        // TODO (apanicke): Remove when the service is ready, right now it makes debugging much more
        // convenient
        try {
            return builder.useContext(context)
                    .useDefaults()
                    .fromMediaMetadata(data)
                    .setMediaId("currsong")
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build Metadata from MediaMetadata, returning empty data", e);
            return empty_data();
        }
    }

    /** Translate a list of MediaSession.QueueItem to a list of audio_util's Metadata */
    public static List<Metadata> toMetadataList(
            Context context, List<MediaSession.QueueItem> items) {
        ArrayList<Metadata> list = new ArrayList<>();

        if (items == null) return list;

        for (int i = 0; i < items.size(); i++) {
            Metadata data = toMetadata(context, items.get(i));
            if (isEmptyData(data)) {
                Log.e(TAG, "Received an empty Metadata item in list. Returning an empty queue");
                return new ArrayList<>();
            }
            data.trackNum = "" + (i + 1);
            data.numTracks = "" + items.size();
            list.add(data);
        }

        return list;
    }

    public static String getDisplayName(Context context, String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Exception e) {
            Log.w(TAG, "Name Not Found using package name: " + packageName);
            return packageName;
        }
    }
}
