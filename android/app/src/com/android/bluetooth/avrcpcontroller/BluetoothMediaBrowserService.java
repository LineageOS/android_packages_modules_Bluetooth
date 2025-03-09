/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;

import com.android.bluetooth.BluetoothPrefs;
import com.android.bluetooth.R;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the MediaBrowserService interface to AVRCP and A2DP
 *
 * <p>This service provides a means for external applications to access A2DP and AVRCP. The
 * applications are expected to use MediaBrowser (see API) and all the music
 * browsing/playback/metadata can be controlled via MediaBrowser and MediaController.
 *
 * <p>The current behavior of MediaSessionCompat exposed by this service is as follows: 1.
 * MediaSessionCompat is active (i.e. SystemUI and other overview UIs can see updates) when device
 * is connected and first starts playing. Before it starts playing we do not activate the session.
 * 1.1 The session is active throughout the duration of connection. 2. The session is de-activated
 * when the device disconnects. It will be connected again when (1) happens.
 */
public class BluetoothMediaBrowserService extends MediaBrowserServiceCompat {
    private static final String TAG = BluetoothMediaBrowserService.class.getSimpleName();

    private static final Object INSTANCE_LOCK = new Object();

    @GuardedBy("INSTANCE_LOCK")
    private static BluetoothMediaBrowserService sBluetoothMediaBrowserService;

    private MediaSessionCompat mSession;

    // Browsing related structures.
    private final List<MediaSessionCompat.QueueItem> mMediaQueue = new ArrayList<>();

    // Media Framework Content Style constants
    private static final String CONTENT_STYLE_SUPPORTED =
            "android.media.browse.CONTENT_STYLE_SUPPORTED";
    public static final String CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    public static final String CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    public static final int CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1;
    public static final int CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2;

    // Error messaging extras
    public static final String ERROR_RESOLUTION_ACTION_INTENT =
            "android.media.extras.ERROR_RESOLUTION_ACTION_INTENT";
    public static final String ERROR_RESOLUTION_ACTION_LABEL =
            "android.media.extras.ERROR_RESOLUTION_ACTION_LABEL";

    // Receiver for making sure our error message text matches the system locale
    private class LocaleChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_LOCALE_CHANGED)) {
                Log.d(TAG, "Locale has updated");

                BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
                if (service == null) {
                    Log.w(TAG, "onReceive(): Got locale update, but service isn't active");
                    return;
                }

                MediaSessionCompat session = service.getSession();

                // Update playback state error message under new locale, if applicable
                MediaControllerCompat controller = session.getController();
                PlaybackStateCompat playbackState =
                        controller == null ? null : controller.getPlaybackState();
                if (playbackState != null && playbackState.getErrorMessage() != null) {
                    setErrorPlaybackState();
                }

                // Update queue title under new locale
                session.setQueueTitle(getString(R.string.bluetooth_a2dp_sink_queue_name));
            }
        }
    }

    private LocaleChangedReceiver mReceiver;

    /**
     * Set the BluetoothMediaBrowserService instance
     *
     * <p>This object is a singleton, as their can only be one service instance active for a process
     * at a time.
     */
    private static void setInstance(BluetoothMediaBrowserService service) {
        synchronized (INSTANCE_LOCK) {
            sBluetoothMediaBrowserService = service;
            Log.i(TAG, "Service set to " + service);
        }
    }

    /** Get the BluetoothMediaBrowserService instance */
    @VisibleForTesting
    public static BluetoothMediaBrowserService getInstance() {
        synchronized (INSTANCE_LOCK) {
            return sBluetoothMediaBrowserService;
        }
    }

    /**
     * Initialize this BluetoothMediaBrowserService, creating our MediaSessionCompat, MediaPlayer
     * and MediaMetaData, and setting up mechanisms to talk with the AvrcpControllerService.
     */
    @Override
    public void onCreate() {
        Log.d(TAG, "Service Created");
        super.onCreate();

        // Create and configure the MediaSessionCompat
        mSession = new MediaSessionCompat(this, TAG);
        setSessionToken(mSession.getSessionToken());
        mSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setQueueTitle(getString(R.string.bluetooth_a2dp_sink_queue_name));
        mSession.setQueue(mMediaQueue);
        setErrorPlaybackState();

        mReceiver = new LocaleChangedReceiver();
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        registerReceiver(mReceiver, filter);

        setInstance(this);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service Destroyed");
        super.onDestroy();
        unregisterReceiver(mReceiver);
        mReceiver = null;
        mSession.release();
        mSession = null;
        setInstance(null);
    }

    /**
     * BrowseResult is used to return the contents of a node along with a status. The status is used
     * to indicate success, a pending download, or error conditions. BrowseResult is used in
     * onLoadChildren() and getContents() in BluetoothMediaBrowserService and in getContents() in
     * AvrcpControllerService. The following statuses have been implemented: 1. SUCCESS - Contents
     * have been retrieved successfully. 2. DOWNLOAD_PENDING - Download is in progress and may or
     * may not have contents to return. 3. NO_DEVICE_CONNECTED - If no device is connected there are
     * no contents to be retrieved. 4. ERROR_MEDIA_ID_INVALID - Contents could not be retrieved as
     * the media ID is invalid. 5. ERROR_NO_AVRCP_SERVICE - Contents could not be retrieved as
     * AvrcpControllerService is not connected.
     */
    record BrowseResult(List<MediaItem> results, byte status) {
        // Possible statuses for onLoadChildren
        public static final byte SUCCESS = 0x00;
        public static final byte DOWNLOAD_PENDING = 0x01;
        public static final byte NO_DEVICE_CONNECTED = 0x02;
        public static final byte ERROR_MEDIA_ID_INVALID = 0x03;
        public static final byte ERROR_NO_AVRCP_SERVICE = 0x04;

        String getStatusString() {
            switch (status) {
                case DOWNLOAD_PENDING:
                    return "DOWNLOAD_PENDING";
                case SUCCESS:
                    return "SUCCESS";
                case NO_DEVICE_CONNECTED:
                    return "NO_DEVICE_CONNECTED";
                case ERROR_MEDIA_ID_INVALID:
                    return "ERROR_MEDIA_ID_INVALID";
                case ERROR_NO_AVRCP_SERVICE:
                    return "ERROR_NO_AVRCP_SERVICE";
                default:
                    return "UNDEFINED_ERROR_CASE";
            }
        }
    }

    BrowseResult getContents(final String parentMediaId) {
        AvrcpControllerService avrcpControllerService =
                AvrcpControllerService.getAvrcpControllerService();
        if (avrcpControllerService == null) {
            Log.w(TAG, "getContents(id=" + parentMediaId + "): AVRCP Controller Service not ready");
            return new BrowseResult(null, BrowseResult.ERROR_NO_AVRCP_SERVICE);
        } else {
            return avrcpControllerService.getContents(parentMediaId);
        }
    }

    private void setErrorPlaybackState() {
        Bundle extras = new Bundle();
        extras.putString(
                ERROR_RESOLUTION_ACTION_LABEL, getString(R.string.bluetooth_connect_action));
        Intent launchIntent = new Intent();
        launchIntent.setAction(BluetoothPrefs.BLUETOOTH_SETTING_ACTION);
        launchIntent.addCategory(BluetoothPrefs.BLUETOOTH_SETTING_CATEGORY);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        getApplicationContext(),
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        extras.putParcelable(ERROR_RESOLUTION_ACTION_INTENT, pendingIntent);
        PlaybackStateCompat errorState =
                new PlaybackStateCompat.Builder()
                        .setErrorMessage(getString(R.string.bluetooth_disconnected))
                        .setExtras(extras)
                        .setState(PlaybackStateCompat.STATE_ERROR, 0, 0)
                        .build();
        mSession.setPlaybackState(errorState);
    }

    private static Bundle getDefaultStyle() {
        Bundle style = new Bundle();
        style.putBoolean(CONTENT_STYLE_SUPPORTED, true);
        style.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE);
        style.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
        return style;
    }

    @Override
    public synchronized void onLoadChildren(
            final String parentMediaId, final Result<List<MediaItem>> result) {
        Log.d(TAG, "Request for contents, id= " + parentMediaId);
        BrowseResult contents = getContents(parentMediaId);
        byte status = contents.status();
        List<MediaItem> results = contents.results();
        if (status == BrowseResult.DOWNLOAD_PENDING && results == null) {
            Log.i(TAG, "Download pending - no results, id= " + parentMediaId);
            result.detach();
        } else {
            Log.d(
                    TAG,
                    "Received Contents, id= "
                            + parentMediaId
                            + ", status= "
                            + contents.getStatusString()
                            + ", results="
                            + results);
            result.sendResult(results);
        }
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "Browser Client Connection Request, client='" + clientPackageName + "')");
        Bundle style = getDefaultStyle();
        return new BrowserRoot(BrowseTree.ROOT, style);
    }

    static synchronized void onNowPlayingQueueChanged(BrowseTree.BrowseNode node) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onNowPlayingQueueChanged(node=" + node + "): Service not available");
            return;
        }

        if (node == null) {
            Log.w(TAG, "Received now playing update for null node");
            return;
        }

        if (node.getScope() != AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING) {
            Log.w(TAG, "Received now playing update for node not in now playing scope.");
            return;
        }

        service.setNowPlayingQueue(node.getContents());
    }

    private void setNowPlayingQueue(List<MediaItem> songList) {
        mMediaQueue.clear();
        if (songList != null && songList.size() > 0) {
            for (MediaItem song : songList) {
                mMediaQueue.add(
                        new MediaSessionCompat.QueueItem(
                                song.getDescription(), mMediaQueue.size()));
            }
            mSession.setQueue(mMediaQueue);
        } else {
            mSession.setQueue(null);
        }
        Log.d(TAG, "Now Playing List Changed, queue=" + mMediaQueue);
    }

    private void clearNowPlayingQueue() {
        mMediaQueue.clear();
        mSession.setQueue(null);
    }

    static synchronized void onBrowseNodeChanged(BrowseTree.BrowseNode node) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onBrowseNodeChanged(node=" + node + "): Service not available");
            return;
        }

        if (node == null) {
            Log.w(TAG, "Received browse node update for null node");
            return;
        }

        Log.d(TAG, "Browse Node contents changed, node=" + node);

        int scope = node.getScope();
        if (scope != AvrcpControllerService.BROWSE_SCOPE_VFS
                && scope != AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST) {
            Log.w(TAG, "Received browse tree update for node outside of player or VFS scope");
            return;
        }
        service.notifyChildrenChanged(node.getID());
    }

    static synchronized void onAddressedPlayerChanged(MediaSessionCompat.Callback callback) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "addressedPlayerChanged(callback=" + callback + "): Service not available");
            return;
        }

        if (callback == null) {
            service.setErrorPlaybackState();
            service.clearNowPlayingQueue();
        }
        service.mSession.setCallback(callback);
    }

    static synchronized void onTrackChanged(AvrcpItem track) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "trackChanged(track=" + track + "): Service not available");
            return;
        }

        Log.d(TAG, "Track Changed, track=" + track);
        if (track != null) {
            service.mSession.setMetadata(track.toMediaMetadata());
        } else {
            service.mSession.setMetadata(null);
        }
    }

    static synchronized void onPlaybackStateChanged(PlaybackStateCompat state) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onPlaybackStateChanged(state=" + state + "): Service not available");
            return;
        }

        Log.d(
                TAG,
                "Playback State Changed, state="
                        + AvrcpControllerUtils.playbackStateCompatToString(state));
        service.mSession.setPlaybackState(state);
    }

    /**
     * Notify this MediaBrowserService of changes to audio focus state
     *
     * <p>Temporarily set state to "Connecting" to better interoperate with media center
     * applications.
     *
     * <p>The "Connecting" state is considered an "active" playback state, which will cause clients
     * that don't listen to the media framework's callback for media key events (whoever most
     * recently requested focus + had playback) to think we're the application who most recently
     * updated to an "active" playback state, which in turn will have them show us as the active app
     * in the UI while we wait on the remote device to accept our playback command.
     */
    static synchronized void onAudioFocusStateChanged(int state) {
        if (!Flags.signalConnectingOnFocusGain()) {
            Log.w(TAG, "Feature 'signal_connecting_on_focus_gain' not enabled. Skip");
            return;
        }

        if (state != AudioManager.AUDIOFOCUS_GAIN) {
            return;
        }

        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onAudioFocusStateChanged(state=" + state + "): Service not available");
            return;
        }

        Log.i(
                TAG,
                "onAudioFocusStateChanged(state="
                        + state
                        + "): Focus gained, briefly signal connecting");

        MediaSessionCompat session = service.getSession();
        MediaControllerCompat controller = session.getController();
        PlaybackStateCompat currentState =
                controller == null ? null : controller.getPlaybackState();

        PlaybackStateCompat connectingState = null;
        if (currentState != null) {
            connectingState =
                    new PlaybackStateCompat.Builder(currentState)
                            .setState(
                                    PlaybackStateCompat.STATE_CONNECTING,
                                    currentState.getPosition(),
                                    currentState.getPlaybackSpeed())
                            .build();
            service.mSession.setPlaybackState(connectingState);
            service.mSession.setPlaybackState(currentState);
        } else {
            Log.w(
                    TAG,
                    "onAudioFocusStateChanged(state="
                            + state
                            + "): current playback state is null");
        }
    }

    /** Get playback state */
    public static synchronized PlaybackStateCompat getPlaybackState() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "getPlaybackState(): Service not available");
            return null;
        }

        MediaSessionCompat session = service.getSession();
        if (session == null) return null;
        MediaControllerCompat controller = session.getController();
        PlaybackStateCompat playbackState =
                controller == null ? null : controller.getPlaybackState();
        return playbackState;
    }

    /** Get object for controlling playback */
    public static synchronized MediaControllerCompat.TransportControls getTransportControls() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "getTransportControls(): Service not available");
            return null;
        }
        return service.mSession.getController().getTransportControls();
    }

    /** Set Media session active whenever we have Focus of any kind */
    public static synchronized void setActive(boolean active) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "setActive(active=" + active + "): Service not available");
            return;
        }
        Log.d(TAG, "Setting the session active state to:" + active);
        service.mSession.setActive(active);
    }

    /**
     * Checks if the media session is active or not.
     *
     * @return true if media session is active, false otherwise.
     */
    @VisibleForTesting
    public static synchronized boolean isActive() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "isActive(): Service not available");
            return false;
        }
        return service.mSession.isActive();
    }

    /** Get Media session for updating state */
    public static synchronized MediaSessionCompat getSession() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "getSession(): Service not available");
            return null;
        }
        return service.mSession;
    }

    /** Reset the state of BluetoothMediaBrowserService to that before a device connected */
    public static synchronized void reset() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "reset(): Service not available");
            return;
        }

        service.clearNowPlayingQueue();
        service.mSession.setMetadata(null);
        service.setErrorPlaybackState();
        service.mSession.setCallback(null);
        Log.d(TAG, "Service state has been reset");
    }

    /** Get the state of the BluetoothMediaBrowserService as a debug string */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(TAG).append(":");
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service != null) {
            MediaSessionCompat session = service.getSession();
            MediaControllerCompat controller = session.getController();
            MediaMetadataCompat metadata = controller == null ? null : controller.getMetadata();
            PlaybackStateCompat playbackState =
                    controller == null ? null : controller.getPlaybackState();
            List<MediaSessionCompat.QueueItem> queue =
                    controller == null ? null : controller.getQueue();
            if (metadata != null) {
                sb.append("\n    track={");
                sb.append("title=")
                        .append(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
                sb.append(", artist=")
                        .append(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
                sb.append(", album=")
                        .append(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));
                sb.append(", duration=")
                        .append(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));
                sb.append(", track_number=")
                        .append(metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER));
                sb.append(", total_tracks=")
                        .append(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS));
                sb.append(", genre=")
                        .append(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE));
                sb.append(", album_art=")
                        .append(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI));
                sb.append("}");
            } else {
                sb.append("\n    track=").append(metadata);
            }
            sb.append("\n    playbackState=")
                    .append(AvrcpControllerUtils.playbackStateCompatToString(playbackState));
            sb.append("\n    queue=").append(queue);
            sb.append("\n    internal_queue=").append(service.mMediaQueue);
            sb.append("\n    session active state=").append(isActive());
        } else {
            Log.w(TAG, "dump Unavailable");
            sb.append(" null");
        }
        return sb.toString();
    }
}
