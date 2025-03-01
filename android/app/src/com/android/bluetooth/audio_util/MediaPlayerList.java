/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.Utils;
import com.android.bluetooth.avrcp.AvrcpPassthrough;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is directly responsible of maintaining the list of Browsable Players as well as the
 * list of Addressable Players.
 */
public class MediaPlayerList {
    private static final String TAG = MediaPlayerList.class.getSimpleName();

    static boolean sTesting = false;

    private static final String PACKAGE_SCHEME = "package";
    private static final int NO_ACTIVE_PLAYER = 0;
    private static final int BLUETOOTH_PLAYER_ID = 0;
    private static final String BLUETOOTH_PLAYER_NAME = "Bluetooth Player";
    private static final int ACTIVE_PLAYER_LOGGER_SIZE = 5;
    private static final String ACTIVE_PLAYER_LOGGER_TITLE = "BTAudio Active Player Events";
    private static final int AUDIO_PLAYBACK_STATE_LOGGER_SIZE = 15;
    private static final String AUDIO_PLAYBACK_STATE_LOGGER_TITLE =
            "BTAudio Audio Playback State Events";

    // mediaId's for the now playing list will be in the form of "NowPlayingId[XX]" where [XX]
    // is the Queue ID for the requested item.
    private static final String NOW_PLAYING_ID_PATTERN = Util.NOW_PLAYING_PREFIX + "([0-9]*)";

    // mediaId's for folder browsing will be in the form of [XX][mediaid],  where [XX] is a
    // two digit representation of the player id and [mediaid] is the original media id as a
    // string.
    private static final String BROWSE_ID_PATTERN = "\\d\\d.*";

    private Context mContext;
    private Looper mLooper; // Thread all media player callbacks and timeouts happen on
    private MediaSessionManager mMediaSessionManager;
    private MediaData mCurrMediaData = null;
    private final AudioManager mAudioManager;

    private final BluetoothEventLogger mActivePlayerLogger =
            new BluetoothEventLogger(ACTIVE_PLAYER_LOGGER_SIZE, ACTIVE_PLAYER_LOGGER_TITLE);
    private final BluetoothEventLogger mAudioPlaybackStateLogger =
            new BluetoothEventLogger(
                    AUDIO_PLAYBACK_STATE_LOGGER_SIZE, AUDIO_PLAYBACK_STATE_LOGGER_TITLE);

    private Map<Integer, MediaPlayerWrapper> mMediaPlayers =
            Collections.synchronizedMap(new HashMap<Integer, MediaPlayerWrapper>());
    private Map<String, Integer> mMediaPlayerIds =
            Collections.synchronizedMap(new HashMap<String, Integer>());
    private Map<Integer, BrowsedPlayerWrapper> mBrowsablePlayers =
            Collections.synchronizedMap(new HashMap<Integer, BrowsedPlayerWrapper>());
    private Map<Integer, MediaBrowserWrapper> mMediaBrowserWrappers =
            Collections.synchronizedMap(new HashMap<Integer, MediaBrowserWrapper>());
    private int mActivePlayerId = NO_ACTIVE_PLAYER;
    private int mBrowsingPlayerId = NO_ACTIVE_PLAYER;
    private int mAddressedPlayerId = NO_ACTIVE_PLAYER;

    private MediaUpdateCallback mCallback;
    private boolean mAudioPlaybackIsActive = false;

    private BrowsablePlayerConnector mBrowsablePlayerConnector;

    private MediaPlayerSettingsEventListener mPlayerSettingsListener;

    public interface MediaUpdateCallback {
        void run(MediaData data);

        void run(boolean availablePlayers, boolean addressedPlayers, boolean uids);
    }

    public interface SetBrowsedPlayerCallback {
        void run(int playerId, boolean success, String rootId, int numItems);
    }

    public interface GetFolderItemsCallback {
        void run(String parentId, List<ListItem> items);
    }

    /** Listener for PlayerSettingsManager. */
    public interface MediaPlayerSettingsEventListener {
        /** Called when the active player has changed. */
        void onActivePlayerChanged(MediaPlayerWrapper player);
    }

    public MediaPlayerList(Looper looper, Context context) {
        Log.v(TAG, "Creating MediaPlayerList");

        mLooper = looper;
        mContext = context;

        // Register for intents where available players might have changed
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme(PACKAGE_SCHEME);
        context.registerReceiver(mPackageChangedBroadcastReceiver, pkgFilter);

        mAudioManager = context.getSystemService(AudioManager.class);
        mAudioManager.registerAudioPlaybackCallback(mAudioPlaybackCallback, new Handler(mLooper));

        mMediaSessionManager = context.getSystemService(MediaSessionManager.class);
        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mActiveSessionsChangedListener, null, new Handler(looper));
        mMediaSessionManager.addOnMediaKeyEventSessionChangedListener(
                mContext.getMainExecutor(), mMediaKeyEventSessionChangedListener);
    }

    /**
     * Retrieves the list of active {@link android.media.session.MediaController}, convert them to
     * local {@link MediaController}, converts them again to {@link MediaPlayerWrapper} and add them
     * to the local list ({@link #mMediaPlayers}).
     *
     * <p>If we already received an onActiveSessionsChanged callback, set this player as active,
     * otherwise set the highest priority one as active (first in the list).
     */
    private void constructCurrentPlayers() {
        // Construct the list of current players
        d("Initializing list of current media players");
        List<android.media.session.MediaController> controllers =
                mMediaSessionManager.getActiveSessions(null);

        for (android.media.session.MediaController controller : controllers) {
            if ((controller.getFlags() & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0) {
                // GLOBAL_PRIORITY session is created by Telecom to handle call control key events
                // but Bluetooth Headset profile handles the key events for calls so we don't have
                // to handle these sessions in AVRCP.
                continue;
            }
            addMediaPlayer(controller);
        }

        // If there were any active players and we don't already have one due to the Media
        // Framework Callbacks then set the highest priority one to active
        if (mActivePlayerId == 0 && mMediaPlayers.size() > 0) {
            String packageName = mMediaSessionManager.getMediaKeyEventSessionPackageName();
            if (!TextUtils.isEmpty(packageName) && haveMediaPlayer(packageName)) {
                Log.i(TAG, "Set active player to MediaKeyEvent session = " + packageName);
                setActivePlayer(mMediaPlayerIds.get(packageName));
            } else {
                Log.i(TAG, "Set active player to first default");
                setActivePlayer(1);
            }
        }
    }

    /** Initiates the media and browsable players list. */
    public void init(MediaUpdateCallback callback) {
        Log.v(TAG, "Initializing MediaPlayerList");
        mCallback = callback;

        if (!SystemProperties.getBoolean("bluetooth.avrcp.browsable_media_player.enabled", true)) {
            // Allow to disable BrowsablePlayerConnector with systemproperties.
            // This is useful when for watches because it is not a regular use case

            Log.i(TAG, "init: without Browsable Player");
            constructCurrentPlayers();
            return;
        }

        initPlayersLists();
    }

    private void initPlayersLists() {
        if (Flags.browsingRefactor()) {
            // Instantiate the media players list
            constructCurrentPlayers();
            // Instantiate the browsable players list
            for (ResolveInfo info : getValidBrowsablePlayersPackages()) {
                MediaBrowserWrapper wrapper =
                        new MediaBrowserWrapper(
                                mContext,
                                mLooper,
                                info.serviceInfo.packageName,
                                info.serviceInfo.name);

                if (!havePlayerId(wrapper.getPackageName())) {
                    mMediaPlayerIds.put(wrapper.getPackageName(), getFreeMediaPlayerId());
                }
                mMediaBrowserWrappers.put(mMediaPlayerIds.get(wrapper.getPackageName()), wrapper);
            }
        } else {
            // Build the list of browsable players and afterwards, build the list of media players
            Intent intent = new Intent(android.service.media.MediaBrowserService.SERVICE_INTERFACE);
            // Don't query stopped apps, that would end up unstopping them
            intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
            List<ResolveInfo> playerList =
                    mContext.getApplicationContext()
                            .getPackageManager()
                            .queryIntentServices(intent, PackageManager.MATCH_ALL);

            mBrowsablePlayerConnector =
                    BrowsablePlayerConnector.connectToPlayers(
                            mContext,
                            mLooper,
                            playerList,
                            (List<BrowsedPlayerWrapper> players) -> {
                                Log.i(TAG, "init: Browsable Player list size is " + players.size());

                                // Check to see if the list has been cleaned up before this
                                // completed
                                if (mMediaSessionManager == null) {
                                    return;
                                }

                                for (BrowsedPlayerWrapper wrapper : players) {
                                    // Generate new id and add the browsable player
                                    if (!havePlayerId(wrapper.getPackageName())) {
                                        mMediaPlayerIds.put(
                                                wrapper.getPackageName(), getFreeMediaPlayerId());
                                    }

                                    d(
                                            "Adding Browser Wrapper for "
                                                    + wrapper.getPackageName()
                                                    + " with id "
                                                    + mMediaPlayerIds.get(
                                                            wrapper.getPackageName()));

                                    mBrowsablePlayers.put(
                                            mMediaPlayerIds.get(wrapper.getPackageName()), wrapper);

                                    wrapper.getFolderItems(
                                            wrapper.getRootId(),
                                            (int status,
                                                    String mediaId,
                                                    List<ListItem> results) -> {
                                                d(
                                                        "Got the contents for: "
                                                                + mediaId
                                                                + " : num results="
                                                                + results.size());
                                            });
                                }

                                constructCurrentPlayers();
                            });
        }
    }

    public void cleanup() {
        mCallback = null;
        mContext.unregisterReceiver(mPackageChangedBroadcastReceiver);

        mActivePlayerId = NO_ACTIVE_PLAYER;
        mBrowsingPlayerId = NO_ACTIVE_PLAYER;

        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveSessionsChangedListener);
        mMediaSessionManager.removeOnMediaKeyEventSessionChangedListener(
                mMediaKeyEventSessionChangedListener);
        mMediaSessionManager = null;

        mAudioManager.unregisterAudioPlaybackCallback(mAudioPlaybackCallback);

        mMediaPlayerIds.clear();

        for (MediaPlayerWrapper player : mMediaPlayers.values()) {
            player.cleanup();
        }
        mMediaPlayers.clear();

        if (mBrowsablePlayerConnector != null) {
            mBrowsablePlayerConnector.cleanup();
        }
        if (Flags.browsingRefactor()) {
            for (MediaBrowserWrapper browser : mMediaBrowserWrappers.values()) {
                browser.disconnect();
            }
            mMediaBrowserWrappers.clear();
        } else {
            for (BrowsedPlayerWrapper player : mBrowsablePlayers.values()) {
                player.disconnect();
            }
            mBrowsablePlayers.clear();
        }
    }

    /** returns the current player ID. */
    public int getCurrentPlayerId() {
        if (Flags.setAddressedPlayer()) {
            return mAddressedPlayerId;
        } else if (Flags.browsingRefactor()) {
            return mBrowsingPlayerId;
        } else {
            return BLUETOOTH_PLAYER_ID;
        }
    }

    /** Get the next ID available in the IDs map. */
    int getFreeMediaPlayerId() {
        int id = 1;
        while (mMediaPlayerIds.containsValue(id)) {
            id++;
        }
        return id;
    }

    /** Returns the {@link #MediaPlayerWrapper} with ID matching {@link #mActivePlayerId}. */
    public MediaPlayerWrapper getActivePlayer() {
        return mMediaPlayers.get(mActivePlayerId);
    }

    /** Returns the {@link #MediaPlayerWrapper} with ID matching {@link #mAddressedPlayerId}. */
    public MediaPlayerWrapper getAddressedPlayer() {
        return mMediaPlayers.get(mAddressedPlayerId);
    }

    /**
     * This is used to send passthrough command to media session
     *
     * <p>Note: This is used only by MCP, AVRCP uses AvrcpTargetService.
     */
    public void sendMediaKeyEvent(int key, boolean pushed) {
        if (mMediaSessionManager == null) {
            Log.d(TAG, "Bluetooth is turning off, ignore it");
            return;
        }
        int action = pushed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        KeyEvent event = new KeyEvent(action, AvrcpPassthrough.toKeyCode(key));
        mMediaSessionManager.dispatchMediaKeyEvent(event, false);
    }

    /** Sets the {@link #mBrowsingPlayerId} and returns the number of items in current path */
    public void setBrowsedPlayer(int playerId, String currentPath, SetBrowsedPlayerCallback cb) {
        if (Flags.browsingRefactor()) {
            if (!haveMediaBrowser(playerId)) {
                cb.run(playerId, false, "", 0);
                return;
            }
            MediaBrowserWrapper wrapper = mMediaBrowserWrappers.get(playerId);
            // If player is different than actual or if the given path is wrong, process rootId
            if (playerId != mBrowsingPlayerId || currentPath.equals("")) {
                wrapper.getRootId(
                        (rootId) -> {
                            wrapper.getFolderItems(
                                    rootId,
                                    (parentId, itemList) -> {
                                        cb.run(playerId, true, rootId, itemList.size());
                                    });
                        });
            } else {
                wrapper.getFolderItems(
                        currentPath,
                        (parentId, itemList) -> {
                            cb.run(playerId, true, currentPath, itemList.size());
                        });
            }
            mBrowsingPlayerId = playerId;
        } else {
            // Fix PTS AVRCP/TG/MCN/CB/BI-02-C
            if (Utils.isPtsTestMode()) {
                d("PTS test mode: getPlayerRoot");
                BrowsedPlayerWrapper wrapper = mBrowsablePlayers.get(BLUETOOTH_PLAYER_ID + 1);
                String itemId = wrapper.getRootId();

                wrapper.getFolderItems(
                        itemId,
                        (status, id, results) -> {
                            if (status != BrowsedPlayerWrapper.STATUS_SUCCESS) {
                                cb.run(playerId, playerId == BLUETOOTH_PLAYER_ID, "", 0);
                                return;
                            }
                            cb.run(playerId, playerId == BLUETOOTH_PLAYER_ID, "", results.size());
                        });
                return;
            }
            cb.run(playerId, playerId == BLUETOOTH_PLAYER_ID, "", mBrowsablePlayers.size());
        }
    }

    /** Sets which player the AV/C commands should be addressed to. */
    public int setAddressedPlayer(int playerId) {
        if (!Flags.setAddressedPlayer()) {
            return BLUETOOTH_PLAYER_ID;
        }
        if (mMediaPlayerIds.containsValue(playerId)) {
            mAddressedPlayerId = playerId;
            sendFolderUpdate(false, true, false);
            Log.d(TAG, "setAddressedPlayer to: " + mAddressedPlayerId);
        } else {
            Log.d(TAG, "setAddressedPlayer not updated: " + mAddressedPlayerId);
        }
        return mAddressedPlayerId;
    }

    /** Returns a list valid browsable players. */
    public List<PlayerInfo> getMediaPlayerList() {
        List<PlayerInfo> ret = new ArrayList<PlayerInfo>();
        if (Flags.browsingRefactor()) {
            // Add actual browsable players
            for (MediaBrowserWrapper browser : mMediaBrowserWrappers.values()) {
                Log.i(
                        TAG,
                        "getMediaPlayerList: Added browsable player: " + browser.getPackageName());
                PlayerInfo info = new PlayerInfo();
                info.id = mMediaPlayerIds.get(browser.getPackageName());
                info.name = Util.getDisplayName(mContext, browser.getPackageName());
                info.browsable = true;
                ret.add(info);
            }
            Log.i(TAG, "getMediaPlayerList: number of mediaplayers: " + mMediaPlayers.size());
            // Also list non-browsable players, they can be selected if controller supports it.
            for (MediaPlayerWrapper mediaPlayer : mMediaPlayers.values()) {
                // Skip player if already added as browsable
                if (haveMediaBrowser(mMediaPlayerIds.get(mediaPlayer.getPackageName()))) {
                    continue;
                }
                Log.i(
                        TAG,
                        "getMediaPlayerList: Added non browsable player: "
                                + mediaPlayer.getPackageName());
                PlayerInfo info = new PlayerInfo();
                info.id = mMediaPlayerIds.get(mediaPlayer.getPackageName());
                info.name = Util.getDisplayName(mContext, mediaPlayer.getPackageName());
                info.browsable = false;
                ret.add(info);
            }
        } else {
            PlayerInfo info = new PlayerInfo();
            info.id = BLUETOOTH_PLAYER_ID;
            info.name = BLUETOOTH_PLAYER_NAME;
            info.browsable = true;
            if (mBrowsablePlayers.size() == 0) {
                // Set Bluetooth Player as non-browable if there is not browsable player exist.
                info.browsable = false;
            }
            ret.add(info);
        }
        return ret;
    }

    /**
     * Returns the active queue item id of the active player.
     *
     * <p>If the player's queue is empty, if the active queue item id is unknown or if the {@link
     * android.media.session.PlaybackState} is null, returns an empty string.
     */
    @NonNull
    public String getCurrentMediaId() {
        final MediaPlayerWrapper player = getActivePlayer();
        if (player == null) return "";

        final PlaybackState state = player.getPlaybackState();
        final List<Metadata> queue = player.getCurrentQueue();

        // Disable the now playing list if the player doesn't have a queue or provide an active
        // queue ID that can be used to determine the active song in the queue.
        if (state == null
                || state.getActiveQueueItemId() == MediaSession.QueueItem.UNKNOWN_ID
                || queue.size() == 0) {
            d(
                    "getCurrentMediaId: No active queue item Id sending empty mediaId:"
                            + " PlaybackState="
                            + state);
            return "";
        }

        return Util.NOW_PLAYING_PREFIX + state.getActiveQueueItemId();
    }

    /**
     * Returns the active {@link android.media.session.MediaController}'s metadata, converted to
     * {@link Metadata}.
     */
    @NonNull
    public Metadata getCurrentSongInfo() {
        final MediaPlayerWrapper player = getActivePlayer();
        if (player == null) return Util.empty_data();

        return player.getCurrentMetadata();
    }

    /**
     * Returns the current playing state of the active player.
     *
     * <p>If {@link #mAudioPlaybackIsActive} is true and the returned state is different from {@link
     * android.media.session.PlaybackState.STATE_PLAYING}, returns a copy of the state with playing
     * state {@link android.media.session.PlaybackState.STATE_PLAYING}.
     */
    public PlaybackState getCurrentPlayStatus() {
        final MediaPlayerWrapper player = getActivePlayer();
        if (player == null) return null;

        PlaybackState state = player.getPlaybackState();
        if (mAudioPlaybackIsActive
                && (state == null || state.getState() != PlaybackState.STATE_PLAYING)) {
            return new PlaybackState.Builder()
                    .setState(
                            PlaybackState.STATE_PLAYING,
                            state == null ? 0 : state.getPosition(),
                            1.0f)
                    .build();
        }
        return state;
    }

    /**
     * Returns the current queue of the active player.
     *
     * <p>If there is no queue, returns a list containing only the active player's Metadata.
     *
     * <p>See {@link #getCurrentSongInfo} and {@link #getCurrentMediaId}.
     */
    @NonNull
    public List<Metadata> getNowPlayingList() {
        // Only send the current song for the now playing if there is no active song. See
        // |getCurrentMediaId()| for reasons why there might be no active song.
        if (getCurrentMediaId().equals("")) {
            List<Metadata> ret = new ArrayList<Metadata>();
            Metadata data = getCurrentSongInfo();
            data.mediaId = "";
            ret.add(data);
            return ret;
        }

        return getActivePlayer().getCurrentQueue();
    }

    /**
     * Informs Media that there is a new request to play {@code mediaId}.
     *
     * <p>If the {@code nowPlaying} parameter is true, this will try to select the item from the
     * current active player's queue. Otherwise this means that the item is from a browsable player
     * and this calls {@link MediaBrowserWrapper} to handle the change.
     */
    public void playItem(int playerId, boolean nowPlaying, String mediaId) {
        if (nowPlaying) {
            playNowPlayingItem(mediaId);
        } else {
            playFolderItem(mediaId);
        }
    }

    /**
     * Retrieves the active player and plays item from queue.
     *
     * <p>See {@link #playItem}.
     */
    private void playNowPlayingItem(String mediaId) {
        d("playNowPlayingItem: mediaId=" + mediaId);

        Pattern regex = Pattern.compile(NOW_PLAYING_ID_PATTERN);
        Matcher m = regex.matcher(mediaId);
        if (!m.find()) {
            // This should never happen since we control the media ID's reported
            Log.wtf(
                    TAG,
                    "playNowPlayingItem: Couldn't match mediaId to pattern: mediaId=" + mediaId);
        }

        long queueItemId = Long.parseLong(m.group(1));

        MediaPlayerWrapper player;
        if (Flags.setAddressedPlayer()) {
            player = getAddressedPlayer();
        } else {
            player = getActivePlayer();
        }
        if (player != null) {
            player.playItemFromQueue(queueItemId);
        }
    }

    /**
     * Retrieves the {@link MediaBrowserWrapper} corresponding to the {@code mediaId} and plays it.
     *
     * <p>See {@link #playItem}.
     */
    private void playFolderItem(String mediaId) {
        Log.d(TAG, "playFolderItem: mediaId=" + mediaId);

        if (Flags.browsingRefactor()) {
            if (!haveMediaBrowser(mBrowsingPlayerId)) {
                Log.e(
                        TAG,
                        "playFolderItem: Do not have the a browsable player with ID "
                                + mBrowsingPlayerId);
                return;
            }

            MediaBrowserWrapper wrapper = mMediaBrowserWrappers.get(mBrowsingPlayerId);
            wrapper.playItem(mediaId);
        } else {
            if (!mediaId.matches(BROWSE_ID_PATTERN)) {
                // This should never happen since we control the media ID's reported
                Log.wtf(TAG, "playFolderItem: mediaId didn't match pattern: mediaId=" + mediaId);
            }

            int playerIndex = Integer.parseInt(mediaId.substring(0, 2));
            if (!haveMediaBrowser(playerIndex)) {
                e("playFolderItem: Do not have the a browsable player with ID " + playerIndex);
                return;
            }

            BrowsedPlayerWrapper wrapper = mBrowsablePlayers.get(playerIndex);
            String itemId = mediaId.substring(2);
            if (TextUtils.isEmpty(itemId)) {
                itemId = wrapper.getRootId();
                if (TextUtils.isEmpty(itemId)) {
                    e("playFolderItem: Failed to start playback with an empty media id.");
                    return;
                }
                Log.i(
                        TAG,
                        "playFolderItem: Empty media id, trying with the root id for "
                                + wrapper.getPackageName());
            }
            wrapper.playItem(itemId);
        }
    }

    /** Calls {@code cb} with the list of browsable players as folder items. */
    void getFolderItemsMediaPlayerList(GetFolderItemsCallback cb) {
        Log.d(TAG, "getFolderItemsMediaPlayerList: Sending Media Player list for root directory");

        ArrayList<ListItem> playerList = new ArrayList<ListItem>();
        if (Flags.browsingRefactor()) {
            for (MediaBrowserWrapper browser : mMediaBrowserWrappers.values()) {

                String displayName = Util.getDisplayName(mContext, browser.getPackageName());
                int id = mMediaPlayerIds.get(browser.getPackageName());

                Log.d(TAG, "getFolderItemsMediaPlayerList: Adding player " + displayName);
                Folder playerFolder =
                        new Folder(Utils.formatSimple("%02d", id), false, displayName);
                playerList.add(new ListItem(playerFolder));
            }
        } else {
            for (BrowsedPlayerWrapper player : mBrowsablePlayers.values()) {

                String displayName = Util.getDisplayName(mContext, player.getPackageName());
                int id = mMediaPlayerIds.get(player.getPackageName());

                Log.d(TAG, "getFolderItemsMediaPlayerList: Adding player " + displayName);
                Folder playerFolder =
                        new Folder(Utils.formatSimple("%02d", id), false, displayName);
                playerList.add(new ListItem(playerFolder));
            }
        }
        cb.run("", playerList);
    }

    /**
     * Calls {@code cb} with a list of browsable folders.
     *
     * <p>If {@code mediaId} is empty, {@code cb} will be called with all the browsable players.
     *
     * <p>If {@code mediaId} corresponds to a known {@link MediaBrowserWrapper}, {@code cb} will be
     * called with the folder items list of the {@link MediaBrowserWrapper}.
     */
    public void getFolderItems(int playerId, String mediaId, GetFolderItemsCallback cb) {
        Log.d(TAG, "getFolderItems(): playerId=" + playerId + ", mediaId=" + mediaId);

        if (!Flags.browsingRefactor() && Utils.isPtsTestMode()) {
            // Fix PTS AVRCP/TG/MCN/CB/BI-02-C
            d("PTS test mode: getFolderItems");
            BrowsedPlayerWrapper wrapper = mBrowsablePlayers.get(BLUETOOTH_PLAYER_ID + 1);
            String itemId = mediaId;
            if (mediaId.equals("")) {
                itemId = wrapper.getRootId();
            }

            wrapper.getFolderItems(
                    itemId,
                    (status, id, results) -> {
                        if (status != BrowsedPlayerWrapper.STATUS_SUCCESS) {
                            cb.run(mediaId, new ArrayList<ListItem>());
                            return;
                        }
                        cb.run(mediaId, results);
                    });
            return;
        }

        // The device is requesting the content of the root folder. This folder contains a list of
        // Browsable Media Players displayed as folders with their contents contained within.
        if (mediaId.equals("")) {
            getFolderItemsMediaPlayerList(cb);
            return;
        }

        if (Flags.browsingRefactor()) {
            if (mMediaBrowserWrappers.containsKey(playerId)) {
                MediaBrowserWrapper wrapper = mMediaBrowserWrappers.get(playerId);
                wrapper.getFolderItems(
                        mediaId,
                        (id, results) -> {
                            cb.run(mediaId, results);
                        });
            } else {
                cb.run(mediaId, new ArrayList<ListItem>());
            }
        } else {
            if (!mediaId.matches(BROWSE_ID_PATTERN)) {
                // This should never happen since we control the media ID's reported
                Log.wtf(TAG, "getFolderItems: mediaId didn't match pattern: mediaId=" + mediaId);
            }

            int playerIndex = Integer.parseInt(mediaId.substring(0, 2));
            String itemId = mediaId.substring(2);

            // TODO (apanicke): Add timeouts for looking up folder items since media browsers don't
            // have to respond.
            if (haveMediaBrowser(playerIndex)) {
                BrowsedPlayerWrapper wrapper = mBrowsablePlayers.get(playerIndex);
                if (itemId.equals("")) {
                    Log.i(TAG, "Empty media id, getting the root for " + wrapper.getPackageName());
                    itemId = wrapper.getRootId();
                }

                wrapper.getFolderItems(
                        itemId,
                        (status, id, results) -> {
                            if (status != BrowsedPlayerWrapper.STATUS_SUCCESS) {
                                cb.run(mediaId, new ArrayList<ListItem>());
                                return;
                            }

                            String playerPrefix = Utils.formatSimple("%02d", playerIndex);
                            for (ListItem item : results) {
                                if (item.isFolder) {
                                    item.folder.mediaId = playerPrefix.concat(item.folder.mediaId);
                                } else {
                                    item.song.mediaId = playerPrefix.concat(item.song.mediaId);
                                }
                            }
                            cb.run(mediaId, results);
                        });
                return;
            } else {
                cb.run(mediaId, new ArrayList<ListItem>());
            }
        }
    }

    public boolean isVfsCoverArtEnabled() {
        return Util.areUriImagesSupported();
    }

    /**
     * Adds a {@link MediaController} to the {@link #mMediaPlayers} map and returns its ID.
     *
     * <p>Each {@link MediaController} is mapped to an ID and each ID is mapped to a package name.
     * If the new {@link MediaController}'s package name is already present in {@link
     * #mMediaPlayerIds}, we keep the ID and replace the {@link MediaController}. Otherwise, we add
     * an entry in both {@link #mMediaPlayerIds} and {@link #mMediaPlayers} maps.
     *
     * <p>Also sends the new {@link MediaData} to the AVRCP service.
     */
    @VisibleForTesting
    int addMediaPlayer(MediaController controller) {
        // Each new player has an ID of 1 plus the highest ID. The ID 0 is reserved to signify that
        // there is no active player. If we already have a browsable player for the package, reuse
        // that key.
        String packageName = controller.getPackageName();
        if (!havePlayerId(packageName)) {
            mMediaPlayerIds.put(packageName, getFreeMediaPlayerId());
        }

        int playerId = mMediaPlayerIds.get(packageName);

        // If we already have a controller for the package, then update it with this new controller
        // as the old controller has probably gone stale.
        if (haveMediaPlayer(playerId)) {
            d("Already have a controller for the player: " + packageName + ", updating instead");
            MediaPlayerWrapper player = mMediaPlayers.get(playerId);
            player.updateMediaController(controller);

            // If the media controller we updated was the active player check if the media updated
            if (playerId == mActivePlayerId) {
                sendMediaUpdate(getActivePlayer().getCurrentMediaData());
            }

            return playerId;
        }

        MediaPlayerWrapper newPlayer =
                MediaPlayerWrapperFactory.wrap(mContext, controller, mLooper);

        Log.i(
                TAG,
                "Adding wrapped media player: "
                        + packageName
                        + " at key: "
                        + mMediaPlayerIds.get(controller.getPackageName()));

        mMediaPlayers.put(playerId, newPlayer);
        return playerId;
    }

    /**
     * Adds a {@link android.media.session.MediaController} to the {@link #mMediaPlayers} map and
     * returns its ID. If the {@link android.media.session.MediaController} is null, returns -1.
     *
     * <p>See {@link #addMediaPlayer(MediaController)}.
     */
    int addMediaPlayer(android.media.session.MediaController controller) {
        if (controller == null) {
            e("Trying to add a null MediaController");
            return -1;
        }

        return addMediaPlayer(MediaControllerFactory.wrap(controller));
    }

    /** Retrieves a list of all packages that both are browsable and play audio */
    List<ResolveInfo> getValidBrowsablePlayersPackages() {

        Intent intentPlayer = new Intent(Intent.ACTION_VIEW);
        Intent intentBrowsable =
                new Intent(android.service.media.MediaBrowserService.SERVICE_INTERFACE);

        Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, "1");
        intentPlayer.setDataAndType(uri, "audio/*");
        List<ResolveInfo> audioPlayerList =
                mContext.getApplicationContext()
                        .getPackageManager()
                        .queryIntentActivities(intentPlayer, 0);

        // Don't query stopped apps, that would end up unstopping them
        intentBrowsable.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
        List<ResolveInfo> browsablePlayerList =
                mContext.getApplicationContext()
                        .getPackageManager()
                        .queryIntentServices(intentBrowsable, PackageManager.MATCH_ALL);

        List<ResolveInfo> validBrowsablePackagesList = new ArrayList<>();
        for (ResolveInfo browsablePlayerInfo : browsablePlayerList) {
            for (ResolveInfo audioPlayerInfo : audioPlayerList) {
                if (audioPlayerInfo.activityInfo.packageName != null
                        && audioPlayerInfo.activityInfo.packageName.equals(
                                browsablePlayerInfo.serviceInfo.packageName)) {
                    validBrowsablePackagesList.add(browsablePlayerInfo);
                    break;
                }
            }
        }
        return validBrowsablePackagesList;
    }

    /** Returns true if {@code packageName} is present in {@link #mMediaPlayerIds}. */
    boolean havePlayerId(String packageName) {
        if (packageName == null) return false;
        return mMediaPlayerIds.containsKey(packageName);
    }

    /**
     * Returns true if {@code packageName} is present in {@link #mMediaPlayerIds} and the
     * corresponding ID has a {@link MediaPlayerWrapper} set in {@link #mMediaPlayers}.
     */
    boolean haveMediaPlayer(String packageName) {
        if (!havePlayerId(packageName)) return false;
        int playerId = mMediaPlayerIds.get(packageName);
        return mMediaPlayers.containsKey(playerId);
    }

    /** Returns true if {@code playerId} is present in {@link #mMediaPlayers}. */
    boolean haveMediaPlayer(int playerId) {
        return mMediaPlayers.containsKey(playerId);
    }

    /** Returns true if {@code playerId} is present in {@link #mBrowsablePlayers}. */
    boolean haveMediaBrowser(int playerId) {
        if (Flags.browsingRefactor()) {
            return mMediaBrowserWrappers.containsKey(playerId);
        } else {
            return mBrowsablePlayers.containsKey(playerId);
        }
    }

    /**
     * Removes the entry corresponding to {@code playerId} from {@link #mMediaPlayers} and {@link
     * #mMediaPlayerIds}.
     *
     * <p>If the removed player was the active one, we consider that there is no more active players
     * until we receive a {@link MediaSessionManager.OnActiveSessionsChangedListener} callback
     * saying otherwise.
     *
     * <p>Also sends empty {@link MediaData} to the AVRCP service.
     */
    void removeMediaPlayer(int playerId) {
        if (!haveMediaPlayer(playerId)) {
            e("Trying to remove nonexistent media player: " + playerId);
            return;
        }

        // If we removed the active player, set no player as active until the Media Framework
        // tells us otherwise
        if (playerId == mActivePlayerId && playerId != NO_ACTIVE_PLAYER) {
            getActivePlayer().unregisterCallback();
            if (mAddressedPlayerId == mActivePlayerId) {
                mAddressedPlayerId = NO_ACTIVE_PLAYER;
            }
            mActivePlayerId = NO_ACTIVE_PLAYER;

            List<Metadata> queue = new ArrayList<Metadata>();
            queue.add(Util.empty_data());
            MediaData newData = new MediaData(Util.empty_data(), null, queue);

            sendMediaUpdate(newData);
        }

        final MediaPlayerWrapper wrapper = mMediaPlayers.get(playerId);
        d("Removing media player " + wrapper.getPackageName());
        mMediaPlayers.remove(playerId);
        if (!haveMediaBrowser(playerId)) {
            d(wrapper.getPackageName() + " doesn't have a browse service. Recycle player ID.");
            mMediaPlayerIds.remove(wrapper.getPackageName());
        }
        wrapper.cleanup();
    }

    /**
     * Sets {@code playerId} as the new active player and sends the new player's {@link Mediadata}
     * to the AVRCP service.
     *
     * <p>Also informs the {@link #PlayerSettingsManager} about the change of active player.
     */
    void setActivePlayer(int playerId) {
        if (!haveMediaPlayer(playerId)) {
            e("Player doesn't exist in list(): " + playerId);
            return;
        }

        int previousActivePlayerId = mActivePlayerId;
        MediaPlayerWrapper previousPlayer = getActivePlayer();

        if (playerId == previousActivePlayerId) {
            if (previousPlayer != null) {
                Log.w(TAG, previousPlayer.getPackageName() + " is already the active player");
            }
            return;
        }

        if (previousActivePlayerId != NO_ACTIVE_PLAYER && previousPlayer != null) {
            previousPlayer.unregisterCallback();
        }

        mActivePlayerId = playerId;

        if (Utils.isPtsTestMode()) {
            sendFolderUpdate(true, true, false);
        } else if (Flags.setAddressedPlayer() && Flags.browsingRefactor()) {
            // If the browsing refactor flag is not active, addressed player should always be 0.
            // If the new active player has been set by Addressed player key event
            // We don't send an addressed player update.
            if (mActivePlayerId != mAddressedPlayerId) {
                mAddressedPlayerId = mActivePlayerId;
                Log.d(TAG, "setActivePlayer AddressedPlayer changed to " + mAddressedPlayerId);
                sendFolderUpdate(false, true, false);
            }
        }

        MediaPlayerWrapper player = getActivePlayer();
        if (player == null) return;

        player.registerCallback(mMediaPlayerCallback);
        mActivePlayerLogger.logd(
                TAG, "setActivePlayer(): setting player to " + player.getPackageName());

        if (mPlayerSettingsListener != null) {
            mPlayerSettingsListener.onActivePlayerChanged(player);
        }

        // Ensure that metadata is synced on the new player
        if (!player.isMetadataSynced()) {
            Log.w(TAG, "setActivePlayer(): Metadata not synced on new player");
            return;
        }

        MediaData data = player.getCurrentMediaData();
        if (mAudioPlaybackIsActive) {
            data.state = mCurrMediaData.state;
            Log.d(TAG, "setActivePlayer mAudioPlaybackIsActive=true, state=" + data.state);
        }
        sendMediaUpdate(data);
    }

    /** Informs AVRCP service that there has been an update in browsable players. */
    private void sendFolderUpdate(
            boolean availablePlayers, boolean addressedPlayers, boolean uids) {
        d("sendFolderUpdate");
        if (mCallback == null) {
            return;
        }

        mCallback.run(availablePlayers, addressedPlayers, uids);
    }

    /** Indicates that there have been a {@link MediaData} update for the current active player. */
    private void sendMediaUpdate(MediaData data) {
        d("sendMediaUpdate");
        if (mCallback == null || data == null) {
            return;
        }

        // Always have items in the queue
        if (data.queue.size() == 0) {
            Log.i(TAG, "sendMediaUpdate: Creating a one item queue for a player with no queue");
            data.queue.add(data.metadata);
        }

        Log.d(TAG, "sendMediaUpdate state=" + data.state);
        mCurrMediaData = data;
        mCallback.run(data);
    }

    /**
     * Callback from Media Framework to indicate that the active session changed.
     *
     * <p>As sessions can have multiple {@link android.media.session.MediaController}, we add all
     * the new players, keeping only the highest priority {@link
     * android.media.session.MediaController} per package name (priority is defined by order in the
     * list).
     *
     * <p>Note: This does not set the current active player, only adds the new {@link
     * MediaController} to the {@link #mMediaPlayerIds} and {@link mMediaPlayers} maps.
     *
     * <p>See {@link #onMediaKeyEventSessionChanged}.
     */
    @VisibleForTesting
    final MediaSessionManager.OnActiveSessionsChangedListener mActiveSessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(
                        List<android.media.session.MediaController> newControllers) {
                    synchronized (MediaPlayerList.this) {
                        Log.v(
                                TAG,
                                "onActiveSessionsChanged: number of controllers: "
                                        + newControllers.size());
                        if (newControllers.size() == 0) {
                            if (mPlayerSettingsListener != null) {
                                mPlayerSettingsListener.onActivePlayerChanged(null);
                            }
                            return;
                        }

                        // Apps are allowed to have multiple MediaControllers. If an app does have
                        // multiple controllers then newControllers contains them in highest
                        // priority order. Since we only want to keep the highest priority one,
                        // we keep track of which controllers we updated and skip over ones
                        // we've already looked at.
                        HashSet<String> addedPackages = new HashSet<String>();

                        for (int i = 0; i < newControllers.size(); i++) {
                            if ((newControllers.get(i).getFlags()
                                            & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY)
                                    != 0) {
                                Log.d(
                                        TAG,
                                        "onActiveSessionsChanged: controller: "
                                                + newControllers.get(i).getPackageName()
                                                + " ignored due to global priority flag");
                                continue;
                            }
                            Log.d(
                                    TAG,
                                    "onActiveSessionsChanged: controller: "
                                            + newControllers.get(i).getPackageName());
                            if (addedPackages.contains(newControllers.get(i).getPackageName())) {
                                continue;
                            }

                            addedPackages.add(newControllers.get(i).getPackageName());
                            addMediaPlayer(newControllers.get(i));
                        }
                    }
                }
            };

    /**
     * {@link android.content.BroadcastReceiver} to catch intents indicating package add, change and
     * removal.
     *
     * <p>If a package is removed while its corresponding {@link MediaPlayerWrapper} is present in
     * the {@link #mMediaPlayerIds} and {@link mMediaPlayers} maps, remove it.
     *
     * <p>If a package is added or changed, currently nothing is done. Ideally, this should add it
     * to the {@link #mMediaPlayerIds} and {@link mMediaPlayers} maps.
     *
     * <p>See {@link #removeMediaPlayer} and {@link
     * #addMediaPlayer(android.media.session.MediaController)}
     */
    private final BroadcastReceiver mPackageChangedBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.v(TAG, "mPackageChangedBroadcastReceiver: action: " + action);

                    if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                            || action.equals(Intent.ACTION_PACKAGE_DATA_CLEARED)) {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;

                        String packageName = intent.getData().getSchemeSpecificPart();
                        Integer playerId = mMediaPlayerIds.get(packageName);
                        if (playerId == null) {
                            return;
                        }
                        if (haveMediaPlayer(playerId)) {
                            removeMediaPlayer(playerId);
                        }
                        if (Flags.browsingRefactor()) {
                            if (haveMediaBrowser(playerId)) {
                                Log.i(TAG, "package removed from browsable list: " + packageName);
                                mMediaBrowserWrappers.get(playerId).disconnect();
                                mMediaBrowserWrappers.remove(playerId);
                                sendFolderUpdate(true, false, false);
                            }
                        }
                    } else if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                            || action.equals(Intent.ACTION_PACKAGE_CHANGED)) {
                        String packageName = intent.getData().getSchemeSpecificPart();
                        if (packageName == null) {
                            return;
                        }
                        if (!Flags.browsingRefactor()) {
                            return;
                        }
                        for (ResolveInfo info : getValidBrowsablePlayersPackages()) {

                            // Check if added package is browsable
                            if (!info.serviceInfo.packageName.equals(packageName)) {
                                continue;
                            }
                            // Add it to the existing list
                            MediaBrowserWrapper wrapper =
                                    new MediaBrowserWrapper(
                                            mContext, mLooper, packageName, info.serviceInfo.name);
                            if (havePlayerId(packageName)) {
                                continue;
                            }
                            Log.i(TAG, "package added to browsable list: " + packageName);
                            int mediaId = getFreeMediaPlayerId();
                            mMediaPlayerIds.put(packageName, mediaId);
                            mMediaBrowserWrappers.put(mediaId, wrapper);
                            sendFolderUpdate(true, false, false);
                        }
                    }
                }
            };

    /**
     * Retrieves and sends the current {@link MediaData} of the active player (if present) to the
     * AVRCP service or if there is no active player, sends an empty {@link MediaData}.
     *
     * <p>See {@link #sendMediaUpdate}.
     */
    void updateMediaForAudioPlayback() {
        MediaData currMediaData = null;
        PlaybackState currState = null;
        if (getActivePlayer() == null) {
            Log.d(TAG, "updateMediaForAudioPlayback: no active player");
            PlaybackState.Builder builder =
                    new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f);
            List<Metadata> queue = new ArrayList<Metadata>();
            queue.add(Util.empty_data());
            currMediaData = new MediaData(Util.empty_data(), builder.build(), queue);
        } else {
            currMediaData = getActivePlayer().getCurrentMediaData();
            currState = currMediaData.state;
        }

        if (currState != null && currState.getState() == PlaybackState.STATE_PLAYING) {
            Log.i(TAG, "updateMediaForAudioPlayback: Active player is playing, drop it");
            return;
        }

        if (mAudioPlaybackIsActive) {
            PlaybackState.Builder builder =
                    new PlaybackState.Builder()
                            .setState(
                                    PlaybackState.STATE_PLAYING,
                                    currState == null ? 0 : currState.getPosition(),
                                    1.0f);
            currMediaData.state = builder.build();
        }
        mAudioPlaybackStateLogger.logd(
                TAG, "updateMediaForAudioPlayback: update state=" + currMediaData.state);
        sendMediaUpdate(currMediaData);
    }

    /** For testing purposes only, sets the {@link #mAudioPlaybackIsActive} flag. */
    @VisibleForTesting
    void injectAudioPlaybacActive(boolean isActive) {
        mAudioPlaybackIsActive = isActive;
        updateMediaForAudioPlayback();
    }

    /**
     * Saves the reference to {@link MediaPlayerSettingsEventListener} to be called when the active
     * player changed, so that {@link #PlayerSettingsManager} always has the right player.
     */
    void setPlayerSettingsCallback(MediaPlayerSettingsEventListener listener) {
        mPlayerSettingsListener = listener;
    }

    /**
     * Listens to playback configurations changes, to set the {@link #mAudioPlaybackIsActive} flag.
     */
    private final AudioManager.AudioPlaybackCallback mAudioPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    if (configs == null) {
                        return;
                    }
                    boolean isActive = false;
                    AudioPlaybackConfiguration activeConfig = null;
                    for (AudioPlaybackConfiguration config : configs) {
                        if (config.isActive()
                                && (config.getAudioAttributes().getUsage()
                                        == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                && (config.getAudioAttributes().getContentType()
                                        == AudioAttributes.CONTENT_TYPE_SPEECH)) {
                            activeConfig = config;
                            isActive = true;
                        }
                    }
                    if (isActive != mAudioPlaybackIsActive) {
                        mAudioPlaybackStateLogger.logd(
                                TAG,
                                "onPlaybackConfigChanged: "
                                        + (mAudioPlaybackIsActive ? "Active" : "Non-active")
                                        + " -> "
                                        + (isActive ? "Active" : "Non-active"));
                        if (isActive) {
                            mAudioPlaybackStateLogger.logd(
                                    TAG,
                                    "onPlaybackConfigChanged: " + "active config: " + activeConfig);
                        }
                        mAudioPlaybackIsActive = isActive;
                        updateMediaForAudioPlayback();
                    }
                }
            };

    /**
     * Callback from {@link MediaPlayerWrapper}.
     *
     * <p>{@link #mediaUpdatedCallback} listens for {@link #MediaData} changes on the active player.
     *
     * <p>{@link #sessionUpdatedCallback} is called when the active session is destroyed so we need
     * to remove the media player from the {@link #mMediaPlayerIds} and {@link mMediaPlayers} maps.
     */
    private final MediaPlayerWrapper.Callback mMediaPlayerCallback =
            new MediaPlayerWrapper.Callback() {
                @Override
                public void mediaUpdatedCallback(MediaData data) {
                    if (data.metadata == null) {
                        Log.d(TAG, "mediaUpdatedCallback(): metadata is null");
                        return;
                    }

                    if (data.state == null) {
                        Log.w(TAG, "mediaUpdatedCallback(): Tried to update with null state");
                        return;
                    }

                    if (mAudioPlaybackIsActive
                            && (data.state.getState() != PlaybackState.STATE_PLAYING)) {
                        Log.d(TAG, "Some audio playbacks are still active, drop it");
                        return;
                    }
                    sendMediaUpdate(data);
                }

                @Override
                public void sessionUpdatedCallback(String packageName) {
                    if (haveMediaPlayer(packageName)) {
                        Log.d(TAG, "sessionUpdatedCallback(): packageName: " + packageName);
                        removeMediaPlayer(mMediaPlayerIds.get(packageName));
                    }
                }
            };

    /**
     * Listens for Media key events session changes.
     *
     * <p>The Media session that listens to key events is considered the active session.
     *
     * <p>This will retrieve the {@link android.media.session.MediaController} for this session with
     * the {@code token} provided and set it as the active one.
     *
     * <p>If the {@link android.media.session.MediaController} flags include the {@link
     * MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY}, the session change shall be ignored as this
     * flag is used only by Telecom to handle wired headsets key events.
     *
     * <p>It can happen that {@code token} is null, in such case wecan still check if we have a
     * {@link MediaController} corresponding to {@code packageName} and set it as active.
     */
    @VisibleForTesting
    final MediaSessionManager.OnMediaKeyEventSessionChangedListener
            mMediaKeyEventSessionChangedListener =
                    new MediaSessionManager.OnMediaKeyEventSessionChangedListener() {
                        @Override
                        public void onMediaKeyEventSessionChanged(
                                String packageName, MediaSession.Token token) {
                            if (mMediaSessionManager == null) {
                                Log.w(
                                        TAG,
                                        "onMediaKeyEventSessionChanged(): Unexpected callback "
                                                + "from the MediaSessionManager, pkg"
                                                + packageName);
                                return;
                            }
                            if (TextUtils.isEmpty(packageName)) {
                                return;
                            }
                            if (token != null) {
                                android.media.session.MediaController controller =
                                        new android.media.session.MediaController(mContext, token);
                                if ((controller.getFlags()
                                                & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY)
                                        != 0) {
                                    // Skip adding controller for GLOBAL_PRIORITY session.
                                    Log.i(
                                            TAG,
                                            "onMediaKeyEventSessionChanged,"
                                                    + " ignoring global priority session");
                                    return;
                                }
                                if (!haveMediaPlayer(controller.getPackageName())) {
                                    // Since we have a controller, we can try to to recover by
                                    // adding the
                                    // player and then setting it as active.
                                    Log.w(
                                            TAG,
                                            "onMediaKeyEventSessionChanged(Token): Addressed Player"
                                                + " changed to a player we didn't have a session"
                                                + " for");
                                    addMediaPlayer(controller);
                                }

                                Log.i(
                                        TAG,
                                        "onMediaKeyEventSessionChanged: token="
                                                + controller.getPackageName());
                                setActivePlayer(mMediaPlayerIds.get(controller.getPackageName()));
                            } else {
                                if (!haveMediaPlayer(packageName)) {
                                    e(
                                            "onMediaKeyEventSessionChanged(PackageName): Media key"
                                                + " event session changed to a player we don't have"
                                                + " a session for");
                                    return;
                                }

                                Log.i(
                                        TAG,
                                        "onMediaKeyEventSessionChanged: packageName="
                                                + packageName);
                                setActivePlayer(mMediaPlayerIds.get(packageName));
                            }
                        }
                    };

    /** Dumps all players and browsable players currently listed in this class. */
    public void dump(StringBuilder sb) {
        sb.append("List of MediaControllers: size=").append(mMediaPlayers.size()).append("\n");
        for (int id : mMediaPlayers.keySet()) {
            if (id == mActivePlayerId) {
                sb.append("<Active> ");
            }
            MediaPlayerWrapper player = mMediaPlayers.get(id);
            sb.append("  Media Player ")
                    .append(id)
                    .append(": ")
                    .append(player.getPackageName())
                    .append("\n");
            sb.append(player.toString().replaceAll("(?m)^", "  "));
            sb.append("\n");
        }

        if (Flags.browsingRefactor()) {
            sb.append("List of Browsers: size=").append(mMediaBrowserWrappers.size()).append("\n");
            for (MediaBrowserWrapper player : mMediaBrowserWrappers.values()) {
                sb.append(player.toString().replaceAll("(?m)^", "  "));
                sb.append("\n");
            }
        } else {
            sb.append("List of Browsers: size=").append(mBrowsablePlayers.size()).append("\n");
            for (BrowsedPlayerWrapper player : mBrowsablePlayers.values()) {
                sb.append(player.toString().replaceAll("(?m)^", "  "));
                sb.append("\n");
            }
        }

        mActivePlayerLogger.dump(sb);
        sb.append("\n");
        mAudioPlaybackStateLogger.dump(sb);
        sb.append("\n");
    }

    private static void e(String message) {
        if (sTesting) {
            Log.wtf(TAG, message);
        } else {
            Log.e(TAG, message);
        }
    }

    private static void d(String message) {
        Log.d(TAG, message);
    }
}
