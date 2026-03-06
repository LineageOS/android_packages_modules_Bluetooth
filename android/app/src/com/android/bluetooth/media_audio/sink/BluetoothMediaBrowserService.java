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

package com.android.bluetooth.media_audio.sink;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;

import com.android.bluetooth.BluetoothPrefs;
import com.android.bluetooth.R;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.avrcpcontroller.AvrcpItem;
import com.android.bluetooth.avrcpcontroller.BrowseTree;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private static final Object INSTANCE_LOCK = new Object();

    @GuardedBy("INSTANCE_LOCK")
    private static BluetoothMediaBrowserService sService;

    private MediaSessionCompat mSession;

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

                // Update playback state error message under new locale, if applicable
                PlaybackStateCompat playbackState = getPlaybackState();
                if (playbackState != null && playbackState.getErrorMessage() != null) {
                    setErrorPlaybackState();
                }

                // Update queue title under new locale
                mSession.setQueueTitle(getString(R.string.bluetooth_media_audio_queue_name));
            }
        }
    }

    private LocaleChangedReceiver mReceiver;

    // ---------------------------------------------------------------------------------------------
    // Singleton Management
    // ---------------------------------------------------------------------------------------------

    /**
     * Set the BluetoothMediaBrowserService instance
     *
     * <p>This object is a singleton, as their can only be one service instance active for a process
     * at a time.
     */
    private static void setInstance(BluetoothMediaBrowserService service) {
        synchronized (INSTANCE_LOCK) {
            sService = service;
            Log.i(TAG, "Service set to " + service);
        }
    }

    /** Get the BluetoothMediaBrowserService instance */
    @VisibleForTesting
    public static BluetoothMediaBrowserService getInstance() {
        synchronized (INSTANCE_LOCK) {
            return sService;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Service Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Initialize this BluetoothMediaBrowserService, creating our MediaSessionCompat, MediaPlayer
     * and MediaMetaData, and setting up mechanisms to talk with the AvrcpControllerService.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service Created");

        // Create and configure the MediaSessionCompat
        mSession = new MediaSessionCompat(this, TAG);
        setSessionToken(mSession.getSessionToken());
        mSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setQueueTitle(getString(R.string.bluetooth_media_audio_queue_name));

        if (Flags.mediaAudioServer()) {
            mSession.setCallback(mSessionCallbacks);
            MediaSource.Metadata nullTrack = null;
            setMetadata(nullTrack);
            setNowPlayingQueue(new ArrayList<MediaSource.Metadata>());
        } else {
            MediaMetadataCompat nullTrackOld = null;
            setMetadata(nullTrackOld);
            setNowPlayingList(new ArrayList<MediaItem>());
        }
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

    // ---------------------------------------------------------------------------------------------
    // Browse Interface (from Applications/Clients)
    // ---------------------------------------------------------------------------------------------

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "onGetRoot(client='" + clientPackageName + "', clientUid=" + clientUid + ")");

        if (!Flags.mediaAudioServer()) {
            Bundle style = getDefaultStyle();
            return new BrowserRoot(BrowseTree.ROOT, style);
        }

        MediaSource.BrowseNode root = getBrowserRoot(clientPackageName, clientUid, rootHints);
        return toBrowserRoot(root);
    }

    @Override
    @RequiresPermission(BLUETOOTH_CONNECT)
    public synchronized void onLoadChildren(
            final String parentMediaId, final Result<List<MediaItem>> result) {
        Log.i(TAG, "onLoadChildren(id=" + parentMediaId + ")");

        if (!Flags.mediaAudioServer()) {
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
                                + ", status="
                                + contents.getStatusString()
                                + ", results="
                                + results);
                result.sendResult(results);
            }
            return;
        }

        MediaSource.BrowseResult browseResult = getBrowseNode(parentMediaId);
        MediaSource.BrowseStatus status = browseResult.getStatus();
        List<MediaItem> results = toMediaItemList(browseResult.getResults());

        if (status == MediaSource.BrowseStatus.DOWNLOAD_PENDING && results == null) {
            Log.i(TAG, "onLoadChildren(id=" + parentMediaId + "): Download pending");
            result.detach();
        } else {
            Log.d(
                    TAG,
                    ("onLoadChildren(id= " + parentMediaId + "): Received Contents")
                            + (", status= " + status)
                            + (", results=" + results));
            result.sendResult(results);
        }
    }

    private static Bundle getDefaultStyle() {
        Bundle style = new Bundle();
        style.putBoolean(CONTENT_STYLE_SUPPORTED, true);
        style.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE);
        style.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
        return style;
    }

    // TODO(FLags.media_audio_server): Remove this legacy browse interfaces upon flag deletion,
    // BrowseResult and getContents

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
    public record BrowseResult(List<MediaItem> results, byte status) {
        // Possible statuses for onLoadChildren
        public static final byte SUCCESS = 0x00;
        public static final byte DOWNLOAD_PENDING = 0x01;
        public static final byte NO_DEVICE_CONNECTED = 0x02;
        public static final byte ERROR_MEDIA_ID_INVALID = 0x03;
        public static final byte ERROR_NO_AVRCP_SERVICE = 0x04;

        String getStatusString() {
            return switch (status) {
                case DOWNLOAD_PENDING -> "DOWNLOAD_PENDING";
                case SUCCESS -> "SUCCESS";
                case NO_DEVICE_CONNECTED -> "NO_DEVICE_CONNECTED";
                case ERROR_MEDIA_ID_INVALID -> "ERROR_MEDIA_ID_INVALID";
                case ERROR_NO_AVRCP_SERVICE -> "ERROR_NO_AVRCP_SERVICE";
                default -> "UNDEFINED_ERROR_CASE";
            };
        }
    }

    BrowseResult getContents(final String parentMediaId) {
        final var avrcpController =
                Optional.ofNullable(AdapterService.deprecatedGetAdapterService())
                        .flatMap(AdapterService::getAvrcpControllerService);
        if (avrcpController.isEmpty()) {
            Log.w(TAG, "getContents(id=" + parentMediaId + "): AVRCP Controller Service not ready");
            return new BrowseResult(null, BrowseResult.ERROR_NO_AVRCP_SERVICE);
        } else {
            return avrcpController.get().getContents(parentMediaId);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events From MediaAudioServer
    // ---------------------------------------------------------------------------------------------

    static synchronized void onTrackChanged(MediaSource.Metadata track) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onTrackChanged(track=" + track + "): Service not available");
            return;
        }

        Log.d(TAG, "onTrackChanged(track=" + track + ")");
        service.setMetadata(track);
    }

    static synchronized void onPlaybackStateChanged(MediaSource.PlaybackStatus status) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onPlaybackStateChanged(status=" + status + "): Service not available");
            return;
        }

        Log.d(TAG, "onPlaybackStateChanged(status=" + status + ")");
        service.setPlaybackState(status);
    }

    static synchronized void onNowPlayingQueueChanged(List<MediaSource.Metadata> queue) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onNowPlayingQueueChanged(queue=" + queue + "): Service not available");
            return;
        }

        service.setNowPlayingQueue(queue);
    }

    static synchronized void onBrowseNodeChanged(String id) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onBrowseNodeChanged(id=" + id + "): Service not available");
            return;
        }

        service.onBrowseNodeUpdated(id);
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
    // TODO(FLags.media_audio_server): Make package private once flag is removed
    public static synchronized void onAudioFocusStateChanged(int state) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(
                    TAG,
                    "onAudioFocusStateChanged(state="
                            + MediaAudioUtils.audioFocusToString(state)
                            + "): Service not available");
            return;
        }

        Log.i(
                TAG,
                "onAudioFocusStateChanged(state="
                        + MediaAudioUtils.audioFocusToString(state)
                        + ")");

        if (state == AudioManager.AUDIOFOCUS_LOSS) {
            service.setActive(false);
            return;
        }

        if (state != AudioManager.AUDIOFOCUS_GAIN) {
            return;
        }

        Log.i(
                TAG,
                "onAudioFocusStateChanged(state="
                        + MediaAudioUtils.audioFocusToString(state)
                        + "): Focus gained, become active and briefly signal connecting");

        service.setActive(true);

        PlaybackStateCompat currentState = service.getPlaybackState();
        PlaybackStateCompat connectingState = null;

        // Not all MediaBrowser clients use the MediaSessionManager framework to determine the
        // current source, which IS the source of truth for who can make noise, and uses Audio Focus
        // changes in its criteria. Some just use playback state changes as a proxy for that. As
        // such, we signal "connecting" (specifically a non-Bluetooth state) to have a faux-state
        // change to convince some MediaBrowser clients that our state has "changed" when we get
        // focus. This lets them not use MediaSessionManager and still consider us the active source
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
                            + MediaAudioUtils.audioFocusToString(state)
                            + "): current playback state is null");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events From AVRCP Controller
    // ---------------------------------------------------------------------------------------------

    // TODO(FLags.media_audio_server): Remove all legacy AVRCP methods upon flag deletion, including
    // onTrackChanged, onPlaybackStateChanged, onNowPlayingQueueChanged,  onAddressedPlayerChanged,
    // onBrowseNodeChanged, onShuffleModeChanged, and onRepeatModeChanged

    public static synchronized void onTrackChanged(AvrcpItem track) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "trackChanged(track=" + track + "): Service not available");
            return;
        }

        Log.d(TAG, "Track Changed, track=" + track);
        if (track != null) {
            service.setMetadata(track.toMediaMetadata());
        } else {
            MediaMetadataCompat nullTrack = null;
            service.setMetadata(nullTrack);
        }
    }

    public static synchronized void onPlaybackStateChanged(PlaybackStateCompat state) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onPlaybackStateChanged(state=" + state + "): Service not available");
            return;
        }

        Log.d(TAG, "Playback State Changed, state=" + playbackStateCompatToString(state));
        service.setPlaybackState(state);
    }

    public static synchronized void onNowPlayingQueueChanged(BrowseTree.BrowseNode node) {
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

        service.setNowPlayingList(node.getContents());
    }

    public static synchronized void onAddressedPlayerChanged(MediaSessionCompat.Callback callback) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "addressedPlayerChanged(callback=" + callback + "): Service not available");
            return;
        }

        if (callback == null) {
            service.setErrorPlaybackState();
            service.mSession.setQueue(null);
        }
        service.mSession.setCallback(callback);
    }

    public static synchronized void onBrowseNodeChanged(BrowseTree.BrowseNode node) {
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
        service.notifyChildrenChanged(node.getID());
    }

    public static synchronized void onShuffleModeChanged(int mode) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onShuffleModeChanged(mode=" + mode + "): Service not available");
            return;
        }

        Log.i(TAG, "onShuffleModeChanged(mode=" + mode + ")");
        service.setShuffleMode(mode);
    }

    public static synchronized void onRepeatModeChanged(int mode) {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "onRepeatModeChanged(mode=" + mode + "): Service not available");
            return;
        }

        Log.i(TAG, "onRepeatModeChanged(mode=" + mode + ")");
        service.setRepeatMode(mode);
    }

    // ---------------------------------------------------------------------------------------------
    // (Internal) Session State Management
    // ---------------------------------------------------------------------------------------------

    // Active State

    /**
     * Checks if the media session is active or not.
     *
     * @return true if media session is active, false otherwise.
     */
    public synchronized boolean isActive() {
        return mSession.isActive();
    }

    /** Set Media session active whenever we have Focus of any kind */
    private synchronized void setActive(boolean active) {
        Log.d(TAG, "setActive(active=" + active + ")");
        mSession.setActive(active);
    }

    // Metadata

    public synchronized MediaMetadataCompat getMetadata() {
        MediaControllerCompat controller = mSession.getController();
        return controller == null ? null : controller.getMetadata();
    }

    private void setMetadata(MediaSource.Metadata metadata) {
        MediaMetadataCompat track = toMediaMetadataCompat(metadata);
        Log.d(TAG, "setMetadata(track=" + (track != null ? track.getDescription() : null) + ")");
        mSession.setMetadata(track);
    }

    // Playback State

    public synchronized PlaybackStateCompat getPlaybackState() {
        MediaControllerCompat controller = mSession.getController();
        PlaybackStateCompat playbackState =
                controller == null ? null : controller.getPlaybackState();
        return playbackState;
    }

    private void setPlaybackState(MediaSource.PlaybackStatus status) {
        Log.d(TAG, "setPlaybackState(status=" + status + ")");
        if (status == null) {
            setErrorPlaybackState();
            return;
        }

        PlaybackStateCompat state = toPlaybackStateCompat(status);
        int shuffleInt = toShuffleInt(status.getShuffleMode());
        int repeatInt = toRepeatInt(status.getRepeatMode());
        mSession.setPlaybackState(state);
        mSession.setShuffleMode(shuffleInt);
        mSession.setRepeatMode(repeatInt);
    }

    private synchronized void setErrorPlaybackState() {
        Log.d(TAG, "setErrorPlaybackState()");

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

    // Shuffle/Repeat

    public synchronized int getShuffleMode() {
        MediaControllerCompat controller = mSession.getController();
        return controller.getShuffleMode();
    }

    public synchronized int getRepeatMode() {
        MediaControllerCompat controller = mSession.getController();
        return controller.getRepeatMode();
    }

    private synchronized void setShuffleMode(int mode) {
        mSession.setShuffleMode(mode);
    }

    private synchronized void setRepeatMode(int mode) {
        mSession.setRepeatMode(mode);
    }

    // Now Playing List

    public synchronized List<MediaSessionCompat.QueueItem> getNowPlayingQueue() {
        MediaControllerCompat controller = mSession.getController();
        return controller == null ? null : controller.getQueue();
    }

    private void setNowPlayingQueue(List<MediaSource.Metadata> queue) {
        List<MediaSessionCompat.QueueItem> queueItems = toQueueItemList(queue);
        Log.d(TAG, "setNowPlayingQueue(queue=" + queueItems + ")");
        mSession.setQueue(queueItems);
    }

    // TODO(FLags.media_audio_server): Remove below legacy methods upon flag deletion: setMetadata,
    // setPlaybackState, setNowPlayingList and reset

    private synchronized void setMetadata(MediaMetadataCompat metadata) {
        Log.d(TAG, "setMetadata(track=" + metadata + ")");
        mSession.setMetadata(metadata);
    }

    private synchronized void setPlaybackState(PlaybackStateCompat state) {
        Log.d(TAG, "setPlaybackState(status=" + state + ")");

        if (state == null) {
            setErrorPlaybackState();
            return;
        }

        mSession.setPlaybackState(state);
    }

    private synchronized void setNowPlayingList(List<MediaItem> songList) {
        Log.d(TAG, "setNowPlayingList(queue=" + songList + ")");
        if (songList != null && songList.size() > 0) {
            ArrayList<MediaSessionCompat.QueueItem> queue = new ArrayList<>(songList.size());
            for (MediaItem song : songList) {
                queue.add(new MediaSessionCompat.QueueItem(song.getDescription(), queue.size()));
            }
            mSession.setQueue(queue);
        } else {
            mSession.setQueue(null);
        }
    }

    /** Reset the state of BluetoothMediaBrowserService to that before a device connected */
    public static synchronized void reset() {
        if (Flags.mediaAudioServer()) {
            Log.e(TAG, "reset(): Not supported in current configuration");
            return;
        }

        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "reset(): Service not available");
            return;
        }

        MediaMetadataCompat nullTrackOld = null;
        service.setMetadata(nullTrackOld);
        service.setErrorPlaybackState();
        service.setNowPlayingList(new ArrayList<MediaItem>());
        service.mSession.setCallback(null);

        Log.d(TAG, "Service state has been reset");
    }

    // ---------------------------------------------------------------------------------------------
    // Playback Controls
    // ---------------------------------------------------------------------------------------------

    private final MediaSessionCompat.Callback mSessionCallbacks =
            new MediaSessionCompat.Callback() {
                @Override
                public void onPrepare() {
                    Log.d(TAG, "onPrepare()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.prepare();
                    } else {
                        Log.w(TAG, "onPrepare(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onPlay() {
                    Log.d(TAG, "onPlay()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.play();
                    } else {
                        Log.w(TAG, "onPlay(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onPause() {
                    Log.d(TAG, "onPause()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.pause();
                    } else {
                        Log.w(TAG, "onPause(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onSkipToNext() {
                    Log.d(TAG, "onSkipToNext()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.skipToNext();
                    } else {
                        Log.w(TAG, "onSkipToNext(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onSkipToPrevious() {
                    Log.d(TAG, "onSkipToPrevious()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.skipToPrevious();
                    } else {
                        Log.w(TAG, "onSkipToPrevious(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onSkipToQueueItem(long id) {
                    Log.d(TAG, "onSkipToQueueItem(id=" + id + ")");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.skipToQueueItem(id);
                    } else {
                        Log.w(
                                TAG,
                                "onSkipToQueueItem(id=" + id + "): MediaAudioServer not available");
                    }
                }

                @Override
                public void onStop() {
                    Log.d(TAG, "onStop()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.stop();
                    } else {
                        Log.w(TAG, "onStop(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onRewind() {
                    Log.d(TAG, "onRewind()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.rewind();
                    } else {
                        Log.w(TAG, "onRewind(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onFastForward() {
                    Log.d(TAG, "onFastForward()");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.fastForward();
                    } else {
                        Log.w(TAG, "onFastForward(): MediaAudioServer not available");
                    }
                }

                @Override
                public void onPlayFromMediaId(String mediaId, Bundle extras) {
                    Log.d(TAG, "onPlayFromMediaId(mediaId=" + mediaId + ")");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.playFromMediaId(mediaId);
                    } else {
                        Log.w(
                                TAG,
                                "onPlayFromMediaId(mediaId="
                                        + mediaId
                                        + "): MediaAudioServer not available");
                    }
                }

                @Override
                public void onSetRepeatMode(int repeatInt) {
                    Log.d(TAG, "onSetRepeatMode(repeatMode=" + repeatInt + ")");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.setRepeatMode(toRepeatMode(repeatInt));
                    } else {
                        Log.w(
                                TAG,
                                "onSetRepeatMode(repeatMode="
                                        + repeatInt
                                        + "): MediaAudioServer not available");
                    }
                }

                @Override
                public void onSetShuffleMode(int shuffleInt) {
                    Log.d(TAG, "onSetShuffleMode(shuffleMode=" + shuffleInt + ")");
                    MediaAudioServer server = MediaAudioServer.getInstance();
                    if (server != null) {
                        server.setShuffleMode(toShuffleMode(shuffleInt));
                    } else {
                        Log.w(
                                TAG,
                                "onSetShuffleMode(shuffleMode="
                                        + shuffleInt
                                        + "): MediaAudioServer not available");
                    }
                }
            };

    // TODO(FLags.media_audio_server): Remove legacy getTransportControls upon flag deletion

    /** Get object for controlling playback */
    public static synchronized MediaControllerCompat.TransportControls getTransportControls() {
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();
        if (service == null) {
            Log.w(TAG, "getTransportControls(): Service not available");
            return null;
        }
        return service.mSession.getController().getTransportControls();
    }

    // ---------------------------------------------------------------------------------------------
    // (Internal) Browse Request Management
    // ---------------------------------------------------------------------------------------------

    private static MediaSource.BrowseNode getBrowserRoot(
            String clientPackageName, int clientUid, Bundle rootHints) {
        return MediaAudioServer.getRoot(clientPackageName, clientUid, rootHints);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private static MediaSource.BrowseResult getBrowseNode(String parentMediaId) {
        Log.i(TAG, "getBrowseNode(id=" + parentMediaId + ")");

        MediaAudioServer server = MediaAudioServer.getInstance();
        if (server != null) {
            return server.browse(new MediaSource.BrowseRequest(parentMediaId));
        } else {
            Log.w(TAG, "getBrowseNode(id=" + parentMediaId + "): MediaAudioServer not available");
            return new MediaSource.BrowseResult(null, MediaSource.BrowseStatus.ERROR_NO_SERVICE);
        }
    }

    private void onBrowseNodeUpdated(String id) {
        Log.d(TAG, "onBrowseNodeUpdated(id=" + id + "): Contents changed. Clients can redownload");
        notifyChildrenChanged(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Data Translation
    // ---------------------------------------------------------------------------------------------

    // This service operates on the Media Compat version of the media framework, but we don't want
    // to make all parts of the code dependent on a particular version of the media framework. Do
    // data translations to the stack standard, so we can independently update this service to any
    // version of the media framework that we want to.

    public static MediaMetadataCompat toMediaMetadataCompat(MediaSource.Metadata track) {
        if (track == null) {
            return null;
        }

        MediaMetadataCompat.Builder metaDataBuilder = new MediaMetadataCompat.Builder();
        String uuid = UUID.randomUUID().toString();
        Uri coverArtUri = track.getImageUri();
        String uriString = coverArtUri != null ? coverArtUri.toString() : null;
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, uuid);
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle());
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getArtist());
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.getAlbum());
        metaDataBuilder.putLong(
                MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, track.getTrackNumber());
        metaDataBuilder.putLong(
                MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, track.getTotalNumberOfTracks());
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, track.getGenre());
        metaDataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.getDuration());
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uriString);
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uriString);
        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uriString);

        return metaDataBuilder.build();
    }

    public static MediaItem toMediaItem(MediaSource.Metadata track) {
        MediaDescriptionCompat.Builder descriptionBuilder = new MediaDescriptionCompat.Builder();

        descriptionBuilder.setMediaId(UUID.randomUUID().toString());
        descriptionBuilder.setTitle(track.getTitle());
        descriptionBuilder.setIconUri(track.getImageUri());

        int flags = 0x0;
        return new MediaItem(descriptionBuilder.build(), flags);
    }

    public static PlaybackStateCompat toPlaybackStateCompat(MediaSource.PlaybackStatus status) {
        if (status == null) {
            return null;
        }

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder();

        long actions = 0;
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.PREPARE)) {
            actions = actions | PlaybackStateCompat.ACTION_PREPARE;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.PLAY)) {
            actions = actions | PlaybackStateCompat.ACTION_PLAY;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.STOP)) {
            actions |= PlaybackStateCompat.ACTION_STOP;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.PAUSE)) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.REWIND)) {
            actions |= PlaybackStateCompat.ACTION_REWIND;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.FAST_FORWARD)) {
            actions |= PlaybackStateCompat.ACTION_FAST_FORWARD;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.NEXT)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.PREVIOUS)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.REPEAT)) {
            actions |= PlaybackStateCompat.ACTION_SET_REPEAT_MODE;
        }
        if (status.getAvailableActions().contains(MediaSource.PlayerAction.SHUFFLE)) {
            actions |= PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
        }
        builder.setActions(actions);

        int playbackStatusCompat =
                switch (status.getState()) {
                    case MediaSource.PlaybackState.NONE -> PlaybackStateCompat.STATE_NONE;
                    case MediaSource.PlaybackState.STOPPED -> PlaybackStateCompat.STATE_STOPPED;
                    case MediaSource.PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING;
                    case MediaSource.PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED;
                    case MediaSource.PlaybackState.FAST_FORWARDING ->
                            PlaybackStateCompat.STATE_FAST_FORWARDING;
                    case MediaSource.PlaybackState.REWINDING -> PlaybackStateCompat.STATE_REWINDING;
                    case MediaSource.PlaybackState.ERROR -> PlaybackStateCompat.STATE_ERROR;
                    default -> PlaybackStateCompat.STATE_NONE;
                };
        builder.setState(
                playbackStatusCompat, status.getPlaybackPosition(), status.getPlaybackSpeed());

        builder.setActiveQueueItemId(status.getActiveQueueId());

        return builder.build();
    }

    public static @Nullable List<MediaSessionCompat.QueueItem> toQueueItemList(
            List<MediaSource.Metadata> queue) {
        if (queue == null || queue.size() == 0) {
            return null;
        }

        List<MediaSessionCompat.QueueItem> queueItems =
                new ArrayList<MediaSessionCompat.QueueItem>();
        for (MediaSource.Metadata track : queue) {
            MediaItem item = toMediaItem(track);
            queueItems.add(
                    new MediaSessionCompat.QueueItem(item.getDescription(), queueItems.size()));
        }
        return queueItems;
    }

    public static @Nullable List<MediaItem> toMediaItemList(
            List<MediaSource.BrowseNode> browseItems) {
        if (browseItems == null) {
            return null;
        }

        List<MediaItem> mediaItems = new ArrayList<>();
        for (MediaSource.BrowseNode browseItem : browseItems) {
            MediaItem item = toMediaItem(browseItem);
            mediaItems.add(item);
        }
        return mediaItems;
    }

    public static BrowserRoot toBrowserRoot(MediaSource.BrowseNode node) {
        return new BrowserRoot(node.getMediaId(), getDefaultStyle());
    }

    public static MediaItem toMediaItem(MediaSource.BrowseNode item) {
        MediaDescriptionCompat.Builder descriptionBuilder = new MediaDescriptionCompat.Builder();

        descriptionBuilder.setMediaId(item.getMediaId());

        MediaSource.Metadata metadata = item.getMetadata();
        if (metadata != null) {
            descriptionBuilder.setTitle(metadata.getTitle());
            descriptionBuilder.setIconUri(metadata.getImageUri());
        }

        int flags = 0x0;
        if (item.isPlayable()) {
            flags |= MediaItem.FLAG_PLAYABLE;
        }

        if (item.isBrowsable()) {
            flags |= MediaItem.FLAG_BROWSABLE;
        }

        return new MediaItem(descriptionBuilder.build(), flags);
    }

    public static MediaSource.ShuffleMode toShuffleMode(int mode) {
        return switch (mode) {
            case PlaybackStateCompat.SHUFFLE_MODE_NONE -> MediaSource.ShuffleMode.OFF;
            case PlaybackStateCompat.SHUFFLE_MODE_GROUP -> MediaSource.ShuffleMode.GROUP;
            case PlaybackStateCompat.SHUFFLE_MODE_ALL -> MediaSource.ShuffleMode.ALL;
            default -> MediaSource.ShuffleMode.OFF;
        };
    }

    public static MediaSource.RepeatMode toRepeatMode(int mode) {
        return switch (mode) {
            case PlaybackStateCompat.REPEAT_MODE_NONE -> MediaSource.RepeatMode.OFF;
            case PlaybackStateCompat.REPEAT_MODE_ONE -> MediaSource.RepeatMode.ONE;
            case PlaybackStateCompat.REPEAT_MODE_GROUP -> MediaSource.RepeatMode.GROUP;
            case PlaybackStateCompat.REPEAT_MODE_ALL -> MediaSource.RepeatMode.ALL;
            default -> MediaSource.RepeatMode.OFF;
        };
    }

    public static int toShuffleInt(MediaSource.ShuffleMode mode) {
        return switch (mode) {
            case MediaSource.ShuffleMode.OFF -> PlaybackStateCompat.SHUFFLE_MODE_NONE;
            case MediaSource.ShuffleMode.GROUP -> PlaybackStateCompat.SHUFFLE_MODE_GROUP;
            case MediaSource.ShuffleMode.ALL -> PlaybackStateCompat.SHUFFLE_MODE_ALL;
            default -> PlaybackStateCompat.SHUFFLE_MODE_INVALID;
        };
    }

    public static int toRepeatInt(MediaSource.RepeatMode mode) {
        return switch (mode) {
            case MediaSource.RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE;
            case MediaSource.RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE;
            case MediaSource.RepeatMode.GROUP -> PlaybackStateCompat.REPEAT_MODE_GROUP;
            case MediaSource.RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL;
            default -> PlaybackStateCompat.REPEAT_MODE_INVALID;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Debug
    // ---------------------------------------------------------------------------------------------

    /** Convert an entire PlaybackStateCompat to a string that contains human readable states */
    private static String playbackStateCompatToString(PlaybackStateCompat playbackState) {
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
    private static String playbackStateToString(int playbackState) {
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

    /** Get the state of the BluetoothMediaBrowserService as a debug string */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(TAG).append(":");
        BluetoothMediaBrowserService service = BluetoothMediaBrowserService.getInstance();

        // TODO(Flags.mediaAudioServer): Remove this entire if/else on flag cleanup
        if (Flags.mediaAudioServer()) {
            sb.append("\n    provider=MediaAudioServer");
        } else {
            sb.append("\n    provider=AvrcpControllerService/A2dpSinkService");
        }

        if (service != null) {
            MediaMetadataCompat metadata = service.getMetadata();
            PlaybackStateCompat playbackState = service.getPlaybackState();
            List<MediaSessionCompat.QueueItem> queue = service.getNowPlayingQueue();
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
            sb.append("\n    playbackState=").append(playbackStateCompatToString(playbackState));
            sb.append("\n    queue=").append(queue);
            sb.append("\n    session active state=").append(service.isActive());
        } else {
            Log.w(TAG, "dump Unavailable");
            sb.append("\n    (service is null)");
        }
        return sb.toString();
    }
}
