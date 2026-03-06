/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.SparseArray;

import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerNativeInterface.RemoteFeatures;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.media_audio.sink.BluetoothMediaBrowserService;
import com.android.bluetooth.media_audio.sink.MediaAudioServer;
import com.android.bluetooth.media_audio.sink.MediaSource;
import com.android.bluetooth.media_audio.sink.MediaSource.BrowseRequest;
import com.android.bluetooth.media_audio.sink.MediaSource.BrowseResult;
import com.android.bluetooth.media_audio.sink.MediaSource.BrowseStatus;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provides Bluetooth AVRCP Controller State Machine responsible for all remote control connections
 * and interactions with a remote controllable device.
 */
class AvrcpControllerStateMachine extends StateMachine {
    private static final String TAG = AvrcpControllerStateMachine.class.getSimpleName();

    // 0->99 Events from Outside
    public static final int CONNECT = 1;
    public static final int DISCONNECT = 2;
    public static final int ACTIVE_DEVICE_CHANGE = 3;
    public static final int AUDIO_FOCUS_STATE_CHANGE = 4;

    // 100->199 Internal Events
    protected static final int CLEANUP = 100;
    private static final int CONNECT_TIMEOUT = 101;

    // 200->299 Events from Native
    static final int STACK_EVENT = 200;
    static final int MESSAGE_INTERNAL_CMD_TIMEOUT = 201;

    static final int MESSAGE_PROCESS_SET_ABS_VOL_CMD = 203;
    static final int MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION = 204;
    static final int MESSAGE_PROCESS_TRACK_CHANGED = 205;
    static final int MESSAGE_PROCESS_PLAY_POS_CHANGED = 206;
    static final int MESSAGE_PROCESS_PLAY_STATUS_CHANGED = 207;
    static final int MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION = 208;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS = 209;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE = 210;
    static final int MESSAGE_PROCESS_GET_PLAYER_ITEMS = 211;
    static final int MESSAGE_PROCESS_FOLDER_PATH = 212;
    static final int MESSAGE_PROCESS_SET_BROWSED_PLAYER = 213;
    static final int MESSAGE_PROCESS_SET_ADDRESSED_PLAYER = 214;
    static final int MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED = 215;
    static final int MESSAGE_PROCESS_NOW_PLAYING_CONTENTS_CHANGED = 216;
    static final int MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS = 217;
    static final int MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS = 218;
    static final int MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED = 219;
    static final int MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM = 220;
    static final int MESSAGE_PROCESS_RECEIVED_REMOTE_FEATURES = 221;

    // 300->399 Events for Browsing
    static final int MESSAGE_GET_FOLDER_ITEMS = 300;
    static final int MESSAGE_PLAY_ITEM = 301;
    static final int MSG_AVRCP_PASSTHRU = 302;
    static final int MSG_AVRCP_SET_SHUFFLE = 303;
    static final int MSG_AVRCP_SET_REPEAT = 304;

    // 400->499 Events for Cover Artwork
    static final int MESSAGE_PROCESS_IMAGE_DOWNLOADED = 400;

    // Denotes that we do not have a registration from the AVRCP Target for an Absolute Volume
    // Changed Notification.
    private static final int VOLUME_NOTIFICATION_LABEL_NONE = -1;

    private final AdapterService mAdapterService;
    private final GetFolderList mGetFolderList;
    private final SparseArray<AvrcpPlayer> mAvailablePlayerList;

    private AvrcpControllerVolumeHandler mVolumeHandler;

    @VisibleForTesting final BrowseTree mBrowseTree;

    protected final BluetoothDevice mDevice;
    protected final byte[] mDeviceAddress;
    protected final AvrcpControllerService mService;
    protected final AvrcpControllerNativeInterface mNativeInterface;
    protected final AvrcpCoverArtManager mCoverArtManager;
    protected final Disconnected mDisconnected;
    protected final Connecting mConnecting;
    protected final Connected mConnected;
    protected final Disconnecting mDisconnecting;

    // TODO(Flags.mediaAudioServer): Make this final on flag cleanup, as they're not optional
    private MediaAudioServer mMediaAudioServer = null;
    private AvrcpControllerMediaSource mMediaSource;

    protected int mCoverArtPsm;
    protected int mMostRecentState = STATE_DISCONNECTED;

    private boolean mShouldSendPlayOnFocusRecovery = false;
    private boolean mRemoteControlConnected = false;
    private boolean mBrowsingConnected = false;

    private AvrcpPlayer mAddressedPlayer;
    private int mAddressedPlayerId;

    private int mVolumeNotificationLabel = VOLUME_NOTIFICATION_LABEL_NONE;
    private RemoteFeatures mRemoteFeatures;

    // Number of items to get in a single fetch
    static final int ITEM_PAGE_SIZE = 20;
    static final int CMD_TIMEOUT_MILLIS = 10000;

    AvrcpControllerStateMachine(
            AdapterService adapterService,
            AvrcpControllerService service,
            MediaAudioServer mediaAudioServer,
            BluetoothDevice device,
            AvrcpControllerNativeInterface nativeInterface) {
        super(TAG);
        mAdapterService = adapterService;
        mDevice = device;
        mDeviceAddress = Util.getByteAddress(mDevice);
        mService = service;
        mNativeInterface = requireNonNull(nativeInterface);
        mCoverArtPsm = 0;
        mCoverArtManager = service.getCoverArtManager();

        if (Flags.mediaAudioServer()) {
            mMediaAudioServer = mediaAudioServer;
            mMediaSource = new AvrcpControllerMediaSource();
        }

        mAvailablePlayerList = new SparseArray<>();
        mAddressedPlayerId = AvrcpPlayer.DEFAULT_ID;

        AvrcpPlayer.Builder apb = new AvrcpPlayer.Builder();
        apb.setDevice(mDevice);
        apb.setPlayerId(mAddressedPlayerId);
        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PLAY);
        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PAUSE);
        apb.setSupportedFeature(AvrcpPlayer.FEATURE_STOP);
        apb.setSupportedFeature(AvrcpPlayer.FEATURE_FORWARD);
        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PREVIOUS);
        mAddressedPlayer = apb.build();
        mAvailablePlayerList.put(mAddressedPlayerId, mAddressedPlayer);

        mBrowseTree = new BrowseTree(mAdapterService, mDevice);
        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mDisconnecting = new Disconnecting();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mConnected);
        addState(mDisconnecting);

        mGetFolderList = new GetFolderList();
        addState(mGetFolderList, mConnected);

        setInitialState(mDisconnected);

        debug("State machine created");
    }

    BrowseTree.BrowseNode findNode(String parentMediaId) {
        debug("findNode(mediaId=" + parentMediaId + ")");
        return mBrowseTree.findBrowseNodeByID(parentMediaId);
    }

    /**
     * Get the current connection state
     *
     * @return current State
     */
    public int getState() {
        return mMostRecentState;
    }

    /**
     * Get the underlying device tracked by this state machine
     *
     * @return device in focus
     */
    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /** send the connection event asynchronously */
    public boolean connect(boolean remoteControlConnected, boolean browsingConnected) {
        if (browsingConnected) {
            onBrowsingConnected();
        }
        mRemoteControlConnected = remoteControlConnected;
        sendMessage(CONNECT);
        return true;
    }

    /** send the Disconnect command asynchronously */
    public void disconnect() {
        sendMessage(DISCONNECT);
    }

    /** Get the current playing track */
    public AvrcpItem getCurrentTrack() {
        return mAddressedPlayer.getCurrentTrack();
    }

    @VisibleForTesting
    int getAddressedPlayerId() {
        return mAddressedPlayerId;
    }

    @VisibleForTesting
    SparseArray<AvrcpPlayer> getAvailablePlayers() {
        return mAvailablePlayerList;
    }

    /**
     * Dump the current State Machine to the string builder.
     *
     * @param sb output string
     */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice + " " + this.toString());
        ProfileService.println(sb, "isActive: " + isActive());
        ProfileService.println(sb, "Control: " + mRemoteControlConnected);
        ProfileService.println(sb, "Browsing: " + mBrowsingConnected);
        ProfileService.println(
                sb,
                "Cover Art: "
                        + (mCoverArtManager != null
                                ? mCoverArtManager.getState(mDevice) == STATE_CONNECTED
                                : "false, mCoverArtManager is null"));

        ProfileService.println(sb, "mRemoteFeatures: " + mRemoteFeatures);
        ProfileService.println(sb, "mVolumeNotificationLabel: " + mVolumeNotificationLabel);
        ProfileService.println(sb, "mVolumeHandler: " + mVolumeHandler);

        ProfileService.println(sb, "Addressed Player ID: " + mAddressedPlayerId);
        ProfileService.println(sb, "Browsed Player ID: " + mBrowseTree.getCurrentBrowsedPlayer());
        ProfileService.println(sb, "Available Players (" + mAvailablePlayerList.size() + "): ");
        for (int i = 0; i < mAvailablePlayerList.size(); i++) {
            AvrcpPlayer player = mAvailablePlayerList.valueAt(i);
            boolean isAddressed = (player.getId() == mAddressedPlayerId);
            ProfileService.println(sb, "\t" + (isAddressed ? "(Addressed) " : "") + player);
        }

        List<MediaItem> queue = null;
        if (mBrowseTree.mNowPlayingNode != null) {
            queue = mBrowseTree.mNowPlayingNode.getContents();
        }
        ProfileService.println(sb, "Queue (" + (queue == null ? 0 : queue.size()) + "): " + queue);

        if (Flags.mediaAudioServer()) {
            ProfileService.println(sb, "\nMediaSource:");
            ProfileService.println(sb, mMediaSource.dump());
        }
    }

    @VisibleForTesting
    boolean isActive() {
        return mDevice.equals(mService.getActiveDevice());
    }

    /** Attempt to set the active status for this device */
    public void setDeviceState(int state) {
        sendMessage(ACTIVE_DEVICE_CHANGE, state);
    }

    @Override
    protected void unhandledMessage(Message msg) {
        warn(
                "Unhandled message, state="
                        + getCurrentState()
                        + "msg.what="
                        + eventToString(msg.what));
    }

    synchronized void onBrowsingConnected() {
        mBrowsingConnected = true;
        requestContents(mBrowseTree.mRootNode);
    }

    synchronized void onBrowsingDisconnected() {
        if (!mBrowsingConnected) return;
        mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_ERROR);
        AvrcpItem previousTrack = mAddressedPlayer.getCurrentTrack();
        String previousTrackUuid = previousTrack != null ? previousTrack.getCoverArtUuid() : null;
        mAddressedPlayer.updateCurrentTrack(null);
        mBrowseTree.mNowPlayingNode.setCached(false);
        mBrowseTree.mRootNode.setCached(false);

        if (Flags.mediaAudioServer()) {
            mMediaSource.setNowPlayingQueue(mBrowseTree.mNowPlayingNode.getContents());
            mMediaSource.onBrowseNodeChanged(mBrowseTree.mRootNode.getID());
        } else {
            if (isActive()) {
                BluetoothMediaBrowserService.onNowPlayingQueueChanged(mBrowseTree.mNowPlayingNode);
                BluetoothMediaBrowserService.onBrowseNodeChanged(mBrowseTree.mRootNode);
            }
        }

        removeUnusedArtwork(previousTrackUuid);
        removeUnusedArtworkFromBrowseTree();
        mBrowsingConnected = false;
    }

    synchronized void connectCoverArt() {
        // Called from "connected" state, which assumes either control or browse is connected
        if (mCoverArtManager != null
                && mCoverArtPsm != 0
                && mCoverArtManager.getState(mDevice) != STATE_CONNECTED) {
            debug("Attempting to connect to AVRCP BIP, psm: " + mCoverArtPsm);
            mCoverArtManager.connect(mDevice, /* psm */ mCoverArtPsm);
        }
    }

    synchronized void refreshCoverArt() {
        if (mCoverArtManager != null
                && mCoverArtPsm != 0
                && mCoverArtManager.getState(mDevice) == STATE_CONNECTED) {
            debug("Attempting to refresh AVRCP BIP OBEX session, psm: " + mCoverArtPsm);
            mCoverArtManager.refreshSession(mDevice);
        }
    }

    synchronized void disconnectCoverArt() {
        // Safe to call even if we're not connected
        if (mCoverArtManager != null) {
            debug("Disconnect BIP cover artwork");
            mCoverArtManager.disconnect(mDevice);
        }
    }

    /**
     * Remove an unused cover art image from storage if it's unused by the browse tree and the
     * current track.
     */
    synchronized void removeUnusedArtwork(String previousTrackUuid) {
        debug("removeUnusedArtwork(" + previousTrackUuid + ")");
        if (mCoverArtManager == null) return;
        AvrcpItem currentTrack = getCurrentTrack();
        String currentTrackUuid = currentTrack != null ? currentTrack.getCoverArtUuid() : null;
        if (previousTrackUuid != null) {
            if (!previousTrackUuid.equals(currentTrackUuid)
                    && mBrowseTree.getNodesUsingCoverArt(previousTrackUuid).isEmpty()) {
                mCoverArtManager.removeImage(mDevice, previousTrackUuid);
            }
        }
    }

    /**
     * Queries the browse tree for unused uuids and removes the associated images from storage if
     * the uuid is not used by the current track.
     */
    synchronized void removeUnusedArtworkFromBrowseTree() {
        debug("removeUnusedArtworkFromBrowseTree()");
        if (mCoverArtManager == null) return;
        AvrcpItem currentTrack = getCurrentTrack();
        String currentTrackUuid = currentTrack != null ? currentTrack.getCoverArtUuid() : null;
        List<String> unusedArtwork = mBrowseTree.getAndClearUnusedCoverArt();
        for (String uuid : unusedArtwork) {
            if (!uuid.equals(currentTrackUuid)) {
                mCoverArtManager.removeImage(mDevice, uuid);
            }
        }
    }

    private void notifyNodeChanged(BrowseTree.BrowseNode node) {
        // We should only notify now playing content updates if we're the active device. VFS
        // updates are fine at any time
        int scope = node.getScope();
        if (scope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING) {
            if (Flags.mediaAudioServer()) {
                mMediaSource.setNowPlayingQueue(node.getContents());
            } else {
                if (isActive()) {
                    BluetoothMediaBrowserService.onNowPlayingQueueChanged(node);
                }
            }
        } else {
            if (Flags.mediaAudioServer()) {
                mMediaSource.onBrowseNodeChanged(node.getID());
            } else {
                BluetoothMediaBrowserService.onBrowseNodeChanged(node);
            }
        }
    }

    private void notifyPlaybackStateChanged(
            PlaybackStateCompat state, int shuffleMode, int repeatMode) {
        if (Flags.mediaAudioServer()) {
            mMediaSource.setPlaybackStatus(state, shuffleMode, repeatMode);
        } else {
            if (isActive()) {
                BluetoothMediaBrowserService.onPlaybackStateChanged(state);
                BluetoothMediaBrowserService.onShuffleModeChanged(shuffleMode);
                BluetoothMediaBrowserService.onRepeatModeChanged(repeatMode);
            }
        }
    }

    void requestContents(BrowseTree.BrowseNode node) {
        sendMessage(MESSAGE_GET_FOLDER_ITEMS, node);
        debug("requestContents(node=" + node + ")");
    }

    public void playItem(BrowseTree.BrowseNode node) {
        sendMessage(MESSAGE_PLAY_ITEM, node);
    }

    void nowPlayingContentChanged() {
        removeUnusedArtworkFromBrowseTree();
        requestContents(mBrowseTree.mNowPlayingNode);
    }

    protected class Disconnected extends State {
        @Override
        public void enter() {
            debug("Disconnected: Entered");
            if (mMostRecentState != STATE_DISCONNECTED) {
                sendMessage(CLEANUP);
            }
            mRemoteFeatures = null;
            broadcastConnectionStateChanged(STATE_DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message message) {
            debug("Disconnected: processMessage " + eventToString(message.what));
            switch (message.what) {
                case MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM -> mCoverArtPsm = message.arg1;
                case CONNECT -> {
                    debug("Connect");
                    transitionTo(mConnecting);
                }
                case CLEANUP -> mService.removeStateMachine(AvrcpControllerStateMachine.this);
                // Wait until we're connected to process this
                case ACTIVE_DEVICE_CHANGE -> deferMessage(message);
                default -> {} // Nothing to do
            }
            return true;
        }
    }

    protected class Connecting extends State {
        @Override
        public void enter() {
            debug("Connecting: Enter Connecting");
            broadcastConnectionStateChanged(STATE_CONNECTING);
            transitionTo(mConnected);
        }
    }

    class Connected extends State {
        private int mCurrentlyHeldKey = 0;

        @Override
        public void enter() {
            if (mMostRecentState == STATE_CONNECTING) {
                broadcastConnectionStateChanged(STATE_CONNECTED);
                mService.getBrowseTree().mRootNode.addChild(mBrowseTree.mRootNode);

                if (Flags.mediaAudioServer()) {
                    mMediaAudioServer.registerMediaSource(mMediaSource);
                } else {
                    BluetoothMediaBrowserService.onBrowseNodeChanged(
                            mService.getBrowseTree().mRootNode);
                }

                connectCoverArt(); // only works if we have a valid PSM
                mVolumeHandler =
                        new AvrcpControllerVolumeHandler(
                                mAdapterService,
                                mDevice,
                                mVolumeCallback,
                                getHandler().getLooper());
            } else {
                debug("Connected: Re-entering Connected ");
            }
            super.enter();
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("Connected: processMessage " + eventToString(msg.what));
            switch (msg.what) {
                case ACTIVE_DEVICE_CHANGE -> {
                    if (Flags.mediaAudioServer()) {
                        Log.w(TAG, "MediaAudioServer owns the active device, not AVRCP");
                        break;
                    }

                    int state = msg.arg1;
                    if (state == AvrcpControllerService.DEVICE_STATE_ACTIVE) {
                        BluetoothMediaBrowserService.onAddressedPlayerChanged(mSessionCallbacks);
                        BluetoothMediaBrowserService.onTrackChanged(
                                mAddressedPlayer.getCurrentTrack());
                        BluetoothMediaBrowserService.onPlaybackStateChanged(
                                mAddressedPlayer.getPlaybackState());
                        BluetoothMediaBrowserService.onShuffleModeChanged(
                                mAddressedPlayer.getShuffleMode());
                        BluetoothMediaBrowserService.onRepeatModeChanged(
                                mAddressedPlayer.getRepeatMode());
                        BluetoothMediaBrowserService.onNowPlayingQueueChanged(
                                mBrowseTree.mNowPlayingNode);

                        // If we switch to a device that is playing and we don't have focus, pause
                        int focusState = getFocusState();
                        if (mAddressedPlayer.getPlaybackState().getState()
                                        == PlaybackStateCompat.STATE_PLAYING
                                && focusState == AudioManager.AUDIOFOCUS_NONE) {
                            sendMessage(
                                    MSG_AVRCP_PASSTHRU,
                                    AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        }
                    } else {
                        sendMessage(
                                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        mShouldSendPlayOnFocusRecovery = false;
                    }
                }
                case AUDIO_FOCUS_STATE_CHANGE -> {
                    if (Flags.mediaAudioServer()) {
                        // Media Audio Server handles all this
                        break;
                    }

                    int newState = msg.arg1;
                    debug("Connected: Audio focus changed -> " + newState);
                    BluetoothMediaBrowserService.onAudioFocusStateChanged(newState);
                    switch (newState) {
                        case AudioManager.AUDIOFOCUS_GAIN -> {
                            // Begin playing audio again if we paused the remote
                            if (mShouldSendPlayOnFocusRecovery) {
                                debug("Connected: Regained focus, establishing play status");
                                sendMessage(
                                        MSG_AVRCP_PASSTHRU,
                                        AvrcpControllerService.PASS_THRU_CMD_ID_PLAY);
                            }
                            mShouldSendPlayOnFocusRecovery = false;
                        }
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Temporary loss of focus. Send a courtesy pause if we are playing and
                            // note we should recover
                            if (mAddressedPlayer.getPlaybackState().getState()
                                    == PlaybackStateCompat.STATE_PLAYING) {
                                debug(
                                        "Connected: Transient loss, temporarily pause with intent"
                                                + " to recover");
                                sendMessage(
                                        MSG_AVRCP_PASSTHRU,
                                        AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                                mShouldSendPlayOnFocusRecovery = true;
                            }
                        }
                        case AudioManager.AUDIOFOCUS_LOSS -> {
                            // Permanent loss of focus probably due to another audio app. Send a
                            // courtesy pause
                            debug("Connected: Lost focus, send a courtesy pause");
                            if (mAddressedPlayer.getPlaybackState().getState()
                                    == PlaybackStateCompat.STATE_PLAYING) {
                                sendMessage(
                                        MSG_AVRCP_PASSTHRU,
                                        AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                            }
                            mShouldSendPlayOnFocusRecovery = false;
                        }
                        default -> {} // Nothing to do
                    }
                }
                case MESSAGE_PROCESS_RECEIVED_REMOTE_FEATURES ->
                        onRemoteFeaturesChanged((RemoteFeatures) msg.obj);
                case MESSAGE_PROCESS_SET_ABS_VOL_CMD ->
                        handleSetAbsVolumeRequest(msg.arg1, msg.arg2);
                case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION ->
                        registerAbsoluteVolumeChanged(msg.arg1);
                case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION ->
                        notifyAbsoluteVolumeChanged(msg.arg1);
                case MESSAGE_GET_FOLDER_ITEMS -> transitionTo(mGetFolderList);
                case MESSAGE_PLAY_ITEM -> processPlayItem((BrowseTree.BrowseNode) msg.obj);
                case MSG_AVRCP_PASSTHRU -> passThru(msg.arg1);
                case MSG_AVRCP_SET_REPEAT -> setRepeat(msg.arg1);
                case MSG_AVRCP_SET_SHUFFLE -> setShuffle(msg.arg1);
                case MESSAGE_PROCESS_TRACK_CHANGED -> {
                    AvrcpItem track = (AvrcpItem) msg.obj;
                    AvrcpItem previousTrack = mAddressedPlayer.getCurrentTrack();
                    downloadImageIfNeeded(track);
                    mAddressedPlayer.updateCurrentTrack(track);

                    if (Flags.mediaAudioServer()) {
                        mMediaSource.setMetadata(track);
                        mMediaSource.setPlaybackStatus(
                                mAddressedPlayer.getPlaybackState(),
                                mAddressedPlayer.getShuffleMode(),
                                mAddressedPlayer.getRepeatMode());
                    } else {
                        if (isActive()) {
                            BluetoothMediaBrowserService.onTrackChanged(track);
                            BluetoothMediaBrowserService.onPlaybackStateChanged(
                                    mAddressedPlayer.getPlaybackState());
                        }
                    }

                    if (previousTrack != null) {
                        removeUnusedArtwork(previousTrack.getCoverArtUuid());
                        removeUnusedArtworkFromBrowseTree();
                    }
                }
                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED -> {
                    debug(
                            "Connected: Playback status = "
                                    + AvrcpControllerUtils.playbackStateToString(msg.arg1));
                    mAddressedPlayer.setPlayStatus(msg.arg1);

                    if (Flags.mediaAudioServer()) {
                        // Media Audio Server handles audio focus requests and courtesy play/pause
                        // based on playback state for all sources
                        mMediaSource.setPlaybackStatus(
                                mAddressedPlayer.getPlaybackState(),
                                mAddressedPlayer.getShuffleMode(),
                                mAddressedPlayer.getRepeatMode());
                        return true;
                    }

                    if (!isActive()) {
                        sendMessage(
                                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        return true;
                    }

                    BluetoothMediaBrowserService.onPlaybackStateChanged(
                            mAddressedPlayer.getPlaybackState());

                    int focusState = getFocusState();
                    if (focusState == AudioManager.ERROR) {
                        sendMessage(
                                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        return true;
                    }

                    if (mAddressedPlayer.getPlaybackState().getState()
                                    == PlaybackStateCompat.STATE_PLAYING
                            && focusState == AudioManager.AUDIOFOCUS_NONE) {
                        if (shouldRequestFocus()) {
                            mSessionCallbacks.onPrepare();
                        } else {
                            sendMessage(
                                    MSG_AVRCP_PASSTHRU,
                                    AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        }
                    }
                }
                case MESSAGE_PROCESS_PLAY_POS_CHANGED -> {
                    if (msg.arg2 != -1) {
                        mAddressedPlayer.setPlayTime(msg.arg2);
                        notifyPlaybackStateChanged(
                                mAddressedPlayer.getPlaybackState(),
                                mAddressedPlayer.getShuffleMode(),
                                mAddressedPlayer.getRepeatMode());
                    }
                }
                case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED -> {
                    int oldAddressedPlayerId = mAddressedPlayerId;
                    mAddressedPlayerId = msg.arg1;
                    debug(
                            "Connected: AddressedPlayer changed "
                                    + oldAddressedPlayerId
                                    + " -> "
                                    + mAddressedPlayerId);

                    // The now playing list is tied to the addressed player by specification in
                    // AVRCP 5.9.1. A new addressed player means our now playing content is now
                    // invalid
                    mBrowseTree.mNowPlayingNode.setCached(false);

                    if (Flags.mediaAudioServer()) {
                        mMediaSource.setNowPlayingQueue(mBrowseTree.mNowPlayingNode.getContents());
                    } else {
                        if (isActive()) {
                            debug(
                                    "Connected: Addressed player change has invalidated the now"
                                            + " playing list");
                            BluetoothMediaBrowserService.onNowPlayingQueueChanged(
                                    mBrowseTree.mNowPlayingNode);
                        }
                    }
                    removeUnusedArtworkFromBrowseTree();

                    // For devices that support browsing, we *may* have an AvrcpPlayer with player
                    // metadata already. We could also be in the middle fetching it. If the player
                    // isn't there then we need to ensure that a default Addressed AvrcpPlayer is
                    // created to represent it. It can be updated if/when we do fetch the player.
                    if (!mAvailablePlayerList.contains(mAddressedPlayerId)) {
                        debug(
                                "Connected: Available player set does not contain the new Addressed"
                                        + " Player");
                        AvrcpPlayer.Builder apb = new AvrcpPlayer.Builder();
                        apb.setDevice(mDevice);
                        apb.setPlayerId(mAddressedPlayerId);
                        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PLAY);
                        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PAUSE);
                        apb.setSupportedFeature(AvrcpPlayer.FEATURE_STOP);
                        apb.setSupportedFeature(AvrcpPlayer.FEATURE_FORWARD);
                        apb.setSupportedFeature(AvrcpPlayer.FEATURE_PREVIOUS);
                        mAvailablePlayerList.put(mAddressedPlayerId, apb.build());
                    }

                    // Set our new addressed player object from our set of available players that's
                    // guaranteed to have the addressed player now.
                    mAddressedPlayer = mAvailablePlayerList.get(mAddressedPlayerId);

                    // Fetch metadata including the now playing list. The specification claims that
                    // the player feature bit only indicates if the player *natively* supports a now
                    // playing list. However, now playing is mandatory if browsing is supported,
                    // even if the player doesn't support it. A list of one item can be returned
                    // instead.
                    mNativeInterface.getCurrentMetadata(mDeviceAddress);
                    mNativeInterface.getPlaybackState(mDeviceAddress);
                    requestContents(mBrowseTree.mNowPlayingNode);
                    debug("Connected: AddressedPlayer = " + mAddressedPlayer);
                }
                case MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS -> {
                    mAddressedPlayer.setSupportedPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyPlaybackStateChanged(
                            mAddressedPlayer.getPlaybackState(),
                            mAddressedPlayer.getShuffleMode(),
                            mAddressedPlayer.getRepeatMode());
                }
                case MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS -> {
                    mAddressedPlayer.setCurrentPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyPlaybackStateChanged(
                            mAddressedPlayer.getPlaybackState(),
                            mAddressedPlayer.getShuffleMode(),
                            mAddressedPlayer.getRepeatMode());
                }
                case MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED -> processAvailablePlayerChanged();
                case MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM -> {
                    mCoverArtPsm = msg.arg1;
                    connectCoverArt();
                }
                case MESSAGE_PROCESS_IMAGE_DOWNLOADED -> {
                    AvrcpCoverArtManager.DownloadEvent event =
                            (AvrcpCoverArtManager.DownloadEvent) msg.obj;
                    String uuid = event.uuid();
                    Uri uri = event.uri();
                    debug("Connected: Received image for " + uuid + " at " + uri.toString());

                    // Let the addressed player know we got an image so it can see if the current
                    // track now has cover artwork
                    boolean addedArtwork = mAddressedPlayer.notifyImageDownload(uuid, uri);
                    if (addedArtwork) {
                        if (Flags.mediaAudioServer()) {
                            mMediaSource.setMetadata(mAddressedPlayer.getCurrentTrack());
                        } else if (isActive()) {
                            BluetoothMediaBrowserService.onTrackChanged(
                                    mAddressedPlayer.getCurrentTrack());
                        }
                    }

                    // Let the browse tree know of the newly downloaded image so it can attach it to
                    // all the items that need it. Notify of changed nodes accordingly
                    Set<BrowseTree.BrowseNode> nodes = mBrowseTree.notifyImageDownload(uuid, uri);
                    for (BrowseTree.BrowseNode node : nodes) {
                        notifyNodeChanged(node);
                    }

                    // Delete images that were downloaded and entirely unused
                    if (!addedArtwork && nodes.isEmpty()) {
                        removeUnusedArtwork(uuid);
                        removeUnusedArtworkFromBrowseTree();
                    }
                }
                case DISCONNECT -> transitionTo(mDisconnecting);
                default -> {
                    return super.processMessage(msg);
                }
            }
            return true;
        }

        private void processPlayItem(BrowseTree.BrowseNode node) {
            if (node == null) {
                warn("Connected: Invalid item to play");
                return;
            }
            mNativeInterface.playItem(mDeviceAddress, node.getScope(), node.getBluetoothID(), 0);
        }

        private synchronized void passThru(int cmd) {
            debug(
                    "Connected: Send passthrough command, id= "
                            + cmd
                            + ", key="
                            + AvrcpControllerUtils.passThruIdToString(cmd));
            // Some keys should be held until the next event.
            if (mCurrentlyHeldKey != 0) {
                mNativeInterface.sendPassThroughCommand(
                        mDeviceAddress,
                        mCurrentlyHeldKey,
                        AvrcpControllerService.KEY_STATE_RELEASED);

                if (mCurrentlyHeldKey == cmd) {
                    // Return to prevent starting FF/FR operation again
                    mCurrentlyHeldKey = 0;
                    return;
                } else {
                    // FF/FR is in progress and other operation is desired
                    // so after stopping FF/FR, not returning so that command
                    // can be sent for the desired operation.
                    mCurrentlyHeldKey = 0;
                }
            }

            // Send the pass through.
            mNativeInterface.sendPassThroughCommand(
                    mDeviceAddress, cmd, AvrcpControllerService.KEY_STATE_PRESSED);

            if (isHoldableKey(cmd)) {
                // Release cmd next time a command is sent.
                mCurrentlyHeldKey = cmd;
            } else {
                mNativeInterface.sendPassThroughCommand(
                        mDeviceAddress, cmd, AvrcpControllerService.KEY_STATE_RELEASED);
            }
        }

        private static boolean isHoldableKey(int cmd) {
            return (cmd == AvrcpControllerService.PASS_THRU_CMD_ID_REWIND)
                    || (cmd == AvrcpControllerService.PASS_THRU_CMD_ID_FF);
        }

        private void setRepeat(int repeatMode) {
            mNativeInterface.setPlayerApplicationSettingValues(
                    mDeviceAddress,
                    (byte) 1,
                    new byte[] {PlayerApplicationSettings.REPEAT_STATUS},
                    new byte[] {
                        PlayerApplicationSettings.mapAvrcpPlayerSettingsToBTattribVal(
                                PlayerApplicationSettings.REPEAT_STATUS, repeatMode)
                    });
        }

        private void setShuffle(int shuffleMode) {
            mNativeInterface.setPlayerApplicationSettingValues(
                    mDeviceAddress,
                    (byte) 1,
                    new byte[] {PlayerApplicationSettings.SHUFFLE_STATUS},
                    new byte[] {
                        PlayerApplicationSettings.mapAvrcpPlayerSettingsToBTattribVal(
                                PlayerApplicationSettings.SHUFFLE_STATUS, shuffleMode)
                    });
        }

        private void processAvailablePlayerChanged() {
            debug("Connected: processAvailablePlayerChanged");
            mBrowseTree.mRootNode.setCached(false);
            mBrowseTree.mRootNode.setExpectedChildren(BrowseTree.DEFAULT_FOLDER_SIZE);
            if (Flags.mediaAudioServer()) {
                mMediaSource.onBrowseNodeChanged(mBrowseTree.mRootNode.getID());
            } else {
                BluetoothMediaBrowserService.onBrowseNodeChanged(mBrowseTree.mRootNode);
            }
            removeUnusedArtworkFromBrowseTree();
            requestContents(mBrowseTree.mRootNode);
        }
    }

    // Handle the get folder listing action
    // a) Fetch the listing of folders
    // b) Once completed return the object listing
    class GetFolderList extends State {
        boolean mAbort;
        BrowseTree.BrowseNode mBrowseNode;
        BrowseTree.BrowseNode mNextStep;

        @Override
        public void enter() {
            debug("GetFolderList: Entering GetFolderList");
            // Setup the timeouts.
            sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
            super.enter();
            mAbort = false;
            Message msg = getCurrentMessage();
            if (msg.what == MESSAGE_GET_FOLDER_ITEMS) {
                mBrowseNode = (BrowseTree.BrowseNode) msg.obj;
                debug("GetFolderList: new fetch request, node=" + mBrowseNode);
            }

            if (mBrowseNode == null) {
                transitionTo(mConnected);
            } else if (!mBrowsingConnected) {
                warn("GetFolderList: Browsing not connected, node=" + mBrowseNode);
                transitionTo(mConnected);
            } else {
                int scope = mBrowseNode.getScope();
                if (scope == AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST
                        || scope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING) {
                    mBrowseNode.setExpectedChildren(BrowseTree.DEFAULT_FOLDER_SIZE);
                }
                mBrowseNode.setCached(false);
                navigateToFolderOrRetrieve(mBrowseNode);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("GetFolderList: processMessage " + eventToString(msg.what));
            switch (msg.what) {
                case MESSAGE_PROCESS_GET_FOLDER_ITEMS -> {
                    ArrayList<AvrcpItem> folderList = (ArrayList<AvrcpItem>) msg.obj;
                    int endIndicator = mBrowseNode.getExpectedChildren() - 1;
                    debug("GetFolderList: End " + endIndicator + " received " + folderList.size());

                    // Queue up image download if the item has an image and we don't have it yet
                    // Only do this if the feature is enabled.
                    for (AvrcpItem track : folderList) {
                        if (shouldDownloadBrowsedImages()) {
                            downloadImageIfNeeded(track);
                        } else {
                            track.setCoverArtUuid(null);
                        }
                    }

                    // Always update the node so that the user does not wait forever
                    // for the list to populate.
                    int newSize = mBrowseNode.addChildren(folderList);
                    debug("GetFolderList: Added " + newSize + " items to the browse tree");
                    notifyNodeChanged(mBrowseNode);

                    if (mBrowseNode.getChildrenCount() >= endIndicator
                            || folderList.size() == 0
                            || mAbort) {
                        // If we have fetched all the elements or if the remotes sends us 0 elements
                        // (which can lead us into a loop since mCurrInd does not proceed) we simply
                        // abort.
                        transitionTo(mConnected);
                    } else {
                        // Fetch the next set of items.
                        fetchContents(mBrowseNode);
                        // Reset the timeout message since we are doing a new fetch now.
                        removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    }
                }
                case MESSAGE_PROCESS_SET_BROWSED_PLAYER -> {
                    mBrowseTree.setCurrentBrowsedPlayer(mNextStep.getID(), msg.arg1, msg.arg2);
                    removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                    sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    navigateToFolderOrRetrieve(mBrowseNode);
                }
                case MESSAGE_PROCESS_FOLDER_PATH -> {
                    mBrowseTree.setCurrentBrowsedFolder(mNextStep.getID());
                    mBrowseTree.getCurrentBrowsedFolder().setExpectedChildren(msg.arg1);

                    // AVRCP Specification says, if we're not database aware, we must disconnect and
                    // reconnect our BIP client each time we successfully change path
                    refreshCoverArt();

                    if (mAbort) {
                        transitionTo(mConnected);
                    } else {
                        removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                        navigateToFolderOrRetrieve(mBrowseNode);
                    }
                }
                case MESSAGE_PROCESS_GET_PLAYER_ITEMS -> {
                    debug("GetFolderList: Received new available player items");
                    BrowseTree.BrowseNode rootNode = mBrowseTree.mRootNode;

                    // The specification is not firm on what receiving available player changes
                    // means relative to the existing player IDs, the addressed player and any
                    // currently saved play status, track or now playing list metadata. We're going
                    // to assume nothing and act verbosely, as some devices are known to reuse
                    // Player IDs.
                    if (!rootNode.isCached()) {
                        List<AvrcpPlayer> playerList = (List<AvrcpPlayer>) msg.obj;

                        // Since players hold metadata, including cover art handles that point to
                        // stored images, be sure to save image UUIDs so we can see if we can
                        // remove them from storage after setting our new player object
                        ArrayList<String> coverArtUuids = new ArrayList<>();
                        for (int i = 0; i < mAvailablePlayerList.size(); i++) {
                            AvrcpPlayer player = mAvailablePlayerList.valueAt(i);
                            AvrcpItem track = player.getCurrentTrack();
                            if (track != null && track.getCoverArtUuid() != null) {
                                coverArtUuids.add(track.getCoverArtUuid());
                            }
                        }

                        mAvailablePlayerList.clear();
                        for (AvrcpPlayer player : playerList) {
                            mAvailablePlayerList.put(player.getId(), player);
                        }

                        // If our new set of players contains our addressed player again then we
                        // will replace it and re-download metadata. If not, we'll re-use the old
                        // player to save the metadata queries.
                        if (!mAvailablePlayerList.contains(mAddressedPlayerId)) {
                            debug(
                                    "GetFolderList: Available player set doesn't contain the"
                                            + " addressed player");
                            mAvailablePlayerList.put(mAddressedPlayerId, mAddressedPlayer);
                        } else {
                            debug(
                                    "GetFolderList: Update addressed player with new available"
                                            + " player metadata");
                            mAddressedPlayer = mAvailablePlayerList.get(mAddressedPlayerId);
                            mNativeInterface.getCurrentMetadata(mDeviceAddress);
                            mNativeInterface.getPlaybackState(mDeviceAddress);
                            requestContents(mBrowseTree.mNowPlayingNode);
                        }
                        debug("GetFolderList: AddressedPlayer = " + mAddressedPlayer);

                        // Check old cover art UUIDs for deletion
                        for (String uuid : coverArtUuids) {
                            removeUnusedArtwork(uuid);
                        }

                        // Make sure our browse tree matches our received Available Player set only
                        rootNode.addChildren(playerList);
                        mBrowseTree.setCurrentBrowsedFolder(BrowseTree.ROOT);
                        rootNode.setExpectedChildren(playerList.size());
                        rootNode.setCached(true);
                        notifyNodeChanged(rootNode);
                    }
                    transitionTo(mConnected);
                }
                case MESSAGE_INTERNAL_CMD_TIMEOUT -> {
                    // We have timed out to execute the request, we should simply send
                    // whatever listing we have gotten until now.
                    warn("GetFolderList: Timeout waiting for download, node=" + mBrowseNode);
                    transitionTo(mConnected);
                }
                case MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE -> {
                    // If we have gotten an error for OUT OF RANGE we have
                    // already sent all the items to the client hence simply
                    // transition to Connected state here.
                    transitionTo(mConnected);
                }
                case MESSAGE_GET_FOLDER_ITEMS -> {
                    BrowseTree.BrowseNode requested = (BrowseTree.BrowseNode) msg.obj;
                    if (!mBrowseNode.equals(requested) || requested.isNowPlaying()) {
                        if (shouldAbort(mBrowseNode.getScope(), requested.getScope())) {
                            mAbort = true;
                        }
                        deferMessage(msg);
                        debug(
                                "GetFolderList: Enqueue new request for node="
                                        + requested
                                        + ", abort="
                                        + mAbort);
                    } else {
                        debug("GetFolderList: Ignore request, node=" + requested);
                    }
                }

                default -> {
                    // All of these messages should be handled by parent state immediately.
                    debug(
                            "GetFolderList: Passing message to parent state, type="
                                    + eventToString(msg.what));
                    return false;
                }
            }
            return true;
        }

        /**
         * shouldAbort calculates the cases where fetching the current directory is no longer
         * necessary.
         *
         * @return true: a new folder in the same scope a new player while fetching contents of a
         *     folder false: other cases, specifically Now Playing while fetching a folder
         */
        private static boolean shouldAbort(int currentScope, int fetchScope) {
            if ((currentScope == fetchScope)
                    || (currentScope == AvrcpControllerService.BROWSE_SCOPE_VFS
                            && fetchScope == AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST)) {
                return true;
            }
            return false;
        }

        private void fetchContents(BrowseTree.BrowseNode target) {
            int start = target.getChildrenCount();
            int end =
                    Math.min(
                                    target.getExpectedChildren(),
                                    target.getChildrenCount() + ITEM_PAGE_SIZE)
                            - 1;
            debug(
                    "GetFolderList: fetchContents(title="
                            + target.getID()
                            + ", scope="
                            + target.getScope()
                            + ", start="
                            + start
                            + ", end="
                            + end
                            + ", expected="
                            + target.getExpectedChildren()
                            + ")");
            switch (target.getScope()) {
                case AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST ->
                        mNativeInterface.getPlayerList(mDeviceAddress, start, end);
                case AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING ->
                        mNativeInterface.getNowPlayingList(mDeviceAddress, start, end);
                case AvrcpControllerService.BROWSE_SCOPE_VFS ->
                        mNativeInterface.getFolderList(mDeviceAddress, start, end);
                default ->
                        error(
                                "GetFolderList: Scope "
                                        + target.getScope()
                                        + " cannot be handled here.");
            }
        }

        /* One of several things can happen when trying to get a folder list
         *
         *
         * 0: The folder handle is no longer valid
         * 1: The folder contents can be retrieved directly (NowPlaying, Root, Current)
         * 2: The folder is a browsable player
         * 3: The folder is a non browsable player
         * 4: The folder is not a child of the current folder
         * 5: The folder is a child of the current folder
         *
         */
        private void navigateToFolderOrRetrieve(BrowseTree.BrowseNode target) {
            mNextStep = mBrowseTree.getNextStepToFolder(target);
            debug(
                    "GetFolderList: NAVIGATING From "
                            + mBrowseTree.getCurrentBrowsedFolder().toString()
                            + ", NAVIGATING Toward "
                            + target.toString());
            if (mNextStep == null) {
                return;
            } else if (target.equals(mBrowseTree.mNowPlayingNode)
                    || target.equals(mBrowseTree.mRootNode)
                    || mNextStep.equals(mBrowseTree.getCurrentBrowsedFolder())) {
                fetchContents(mNextStep);
            } else if (mNextStep.isPlayer()) {
                debug("GetFolderList: NAVIGATING Player " + mNextStep.toString());
                BrowseTree.BrowseNode currentBrowsedPlayer = mBrowseTree.getCurrentBrowsedPlayer();
                if (currentBrowsedPlayer != null) {
                    debug(
                            "GetFolderList: Uncache current browsed player, player="
                                    + currentBrowsedPlayer);
                    mBrowseTree.getCurrentBrowsedPlayer().setCached(false);
                } else {
                    debug(
                            "GetFolderList: Browsed player unset, no need to uncache the"
                                    + " previous player");
                }

                if (mNextStep.isBrowsable()) {
                    debug(
                            "GetFolderList: Set browsed player, old="
                                    + currentBrowsedPlayer
                                    + ", new="
                                    + mNextStep);
                    mNativeInterface.setBrowsedPlayer(
                            mDeviceAddress, (int) mNextStep.getBluetoothID());
                } else {
                    debug("GetFolderList: Target player doesn't support browsing");
                    mNextStep.setCached(true);
                    transitionTo(mConnected);
                }
            } else if (mNextStep.equals(mBrowseTree.mNavigateUpNode)) {
                debug("GetFolderList: NAVIGATING UP " + mNextStep.toString());
                mNextStep = mBrowseTree.getCurrentBrowsedFolder().getParent();
                mBrowseTree.getCurrentBrowsedFolder().setCached(false);
                removeUnusedArtworkFromBrowseTree();
                mNativeInterface.changeFolderPath(
                        mDeviceAddress, AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP, 0);

            } else {
                debug("GetFolderList: NAVIGATING DOWN " + mNextStep.toString());
                mNativeInterface.changeFolderPath(
                        mDeviceAddress,
                        AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_DOWN,
                        mNextStep.getBluetoothID());
            }
        }

        @Override
        public void exit() {
            debug("GetFolderList: fetch complete, node=" + mBrowseNode);
            removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);

            // Whatever we have, notify on it so the UI doesn't hang
            if (mBrowseNode != null) {
                mBrowseNode.setCached(true);
                notifyNodeChanged(mBrowseNode);
            }

            mBrowseNode = null;
            super.exit();
        }
    }

    protected class Disconnecting extends State {
        @Override
        public void enter() {
            debug("Disconnecting: Entered Disconnecting");
            disconnectCoverArt();
            onBrowsingDisconnected();
            mService.getBrowseTree().mRootNode.removeChild(mBrowseTree.mRootNode);

            if (Flags.mediaAudioServer()) {
                mMediaAudioServer.unregisterMediaSource(mMediaSource);
            } else {
                BluetoothMediaBrowserService.onBrowseNodeChanged(
                        mService.getBrowseTree().mRootNode);
            }

            mVolumeHandler.stop();
            mVolumeHandler = null;
            broadcastConnectionStateChanged(STATE_DISCONNECTING);
            transitionTo(mDisconnected);
        }
    }

    /** Triggered when the remote device's features are changed/reported. */
    private void onRemoteFeaturesChanged(RemoteFeatures features) {
        debug("onRemoteFeaturesChanged: features=" + features);
        mRemoteFeatures = features;
    }

    /**
     * Handle a request to align our local volume with the volume of a remote device. If we're
     * assuming the source volume is fixed then a response of ABS_VOL_MAX will always be sent and no
     * volume adjustment action will be taken on the sink side.
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     * @param label Volume notification label
     */
    private void handleSetAbsVolumeRequest(int absVol, int label) {
        debug("handleSetAbsVolumeRequest: absVol = " + absVol + ", label = " + label);
        int newVol = mVolumeHandler.setAbsoluteVolume(absVol, label);
        mNativeInterface.sendSetAbsVolRsp(mDeviceAddress, newVol, label);
    }

    private void registerAbsoluteVolumeChanged(int label) {
        if (mVolumeNotificationLabel == VOLUME_NOTIFICATION_LABEL_NONE) {
            debug("registerAbsoluteVolumeChanged: label=" + label);
        } else {
            warn(
                    "registerAbsoluteVolumeChanged: Already registered for label: "
                            + mVolumeNotificationLabel
                            + ". Replacing with new label: "
                            + label);
        }

        mVolumeNotificationLabel = label;
        int absVol = mVolumeHandler.getAbsoluteVolume();
        mNativeInterface.sendRegisterAbsVolInterimRsp(
                mDeviceAddress, absVol, mVolumeNotificationLabel);
    }

    private void notifyAbsoluteVolumeChanged(int absVol) {
        debug(
                "notifyAbsoluteVolumeChanged: absVol="
                        + absVol
                        + ", label="
                        + mVolumeNotificationLabel);
        if (mVolumeNotificationLabel == VOLUME_NOTIFICATION_LABEL_NONE) {
            // We don't have an outstanding registration from the AVRCP Target for an Absolute
            // Volume Changed Notification.
            debug("notifyAbsoluteVolumeChanged: No registered label. Ignoring volume change.");
            return;
        }
        mNativeInterface.sendRegisterAbsVolChangedRsp(
                mDeviceAddress, absVol, mVolumeNotificationLabel);
        mVolumeNotificationLabel = VOLUME_NOTIFICATION_LABEL_NONE;
    }

    private boolean shouldDownloadBrowsedImages() {
        return mService.getResources().getBoolean(R.bool.avrcp_controller_cover_art_browsed_images);
    }

    private void downloadImageIfNeeded(AvrcpItem track) {
        if (mCoverArtManager == null) return;
        String uuid = track.getCoverArtUuid();
        Uri imageUri = null;
        if (uuid != null) {
            imageUri = mCoverArtManager.getImageUri(mDevice, uuid);
            if (imageUri != null) {
                track.setCoverArtLocation(imageUri);
            } else {
                mCoverArtManager.downloadImage(mDevice, uuid);
            }
        }
    }

    // TODO(Flags.mediaAudioServer): Remove after flag clean up, AVRCP and A2DP do not own focus
    // or make decisions based on focus anymore. MediaAudioServer does this.
    private int getFocusState() {
        return mAdapterService
                .getA2dpSinkService()
                .map(A2dpSinkService::getFocusState)
                .orElse(AudioManager.ERROR);
    }

    // TODO(Flags.mediaAudioServer): Remove when flag is cleaned up. This will be unused
    MediaSessionCompat.Callback mSessionCallbacks =
            new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    debug("onPlay");
                    onPrepare();
                    sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PLAY);
                }

                @Override
                public void onPause() {
                    debug("onPause");
                    // If we receive a local pause/stop request and send it out then we need to
                    // signal that
                    // the intent is to stay paused if we recover focus from a transient loss
                    if (getFocusState() == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        debug(
                                "Received a pause while in a transient loss. Do not recover"
                                        + " anymore.");
                        mShouldSendPlayOnFocusRecovery = false;
                    }
                    sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                }

                @Override
                public void onSkipToNext() {
                    debug("onSkipToNext");
                    onPrepare();
                    sendMessage(
                            MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD);
                }

                @Override
                public void onSkipToPrevious() {
                    debug("onSkipToPrevious");
                    onPrepare();
                    sendMessage(
                            MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD);
                }

                @Override
                public void onSkipToQueueItem(long id) {
                    debug("onSkipToQueueItem(id=" + id + ")");
                    onPrepare();
                    BrowseTree.BrowseNode node = mBrowseTree.getTrackFromNowPlayingList((int) id);
                    if (node != null) {
                        sendMessage(MESSAGE_PLAY_ITEM, node);
                    }
                }

                @Override
                public void onStop() {
                    debug("onStop");
                    // If we receive a local pause/stop request and send it out then we need to
                    // signal that
                    // the intent is to stay paused if we recover focus from a transient loss
                    if (getFocusState() == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        debug("Received a stop while in a transient loss. Do not recover anymore.");
                        mShouldSendPlayOnFocusRecovery = false;
                    }
                    sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_STOP);
                }

                @Override
                public void onPrepare() {
                    debug("onPrepare");
                    mAdapterService
                            .getA2dpSinkService()
                            .ifPresent(a2dpSink -> a2dpSink.requestAudioFocus(mDevice, true));
                }

                @Override
                public void onRewind() {
                    debug("onRewind");
                    sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_REWIND);
                }

                @Override
                public void onFastForward() {
                    debug("onFastForward");
                    sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FF);
                }

                @Override
                public void onPlayFromMediaId(String mediaId, Bundle extras) {
                    debug("onPlayFromMediaId(mediaId=" + mediaId + ")");
                    // Play the item if possible.
                    onPrepare();
                    BrowseTree.BrowseNode node = mBrowseTree.findBrowseNodeByID(mediaId);
                    if (node != null) {
                        // node was found on this bluetooth device
                        sendMessage(MESSAGE_PLAY_ITEM, node);
                    } else {
                        // node was not found on this device, pause here, and play on another device
                        sendMessage(
                                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        mService.playItem(mediaId);
                    }
                }

                @Override
                public void onSetRepeatMode(int repeatMode) {
                    debug("onSetRepeatMode(repeatMode=" + repeatMode + ")");
                    sendMessage(MSG_AVRCP_SET_REPEAT, repeatMode);
                }

                @Override
                public void onSetShuffleMode(int shuffleMode) {
                    debug("onSetShuffleMode(shuffleMode=" + shuffleMode + ")");
                    sendMessage(MSG_AVRCP_SET_SHUFFLE, shuffleMode);
                }
            };

    AvrcpControllerVolumeHandler.Callback mVolumeCallback =
            new AvrcpControllerVolumeHandler.Callback() {
                @Override
                public void onAbsoluteVolumeChanged(int absVol) {
                    if (!Flags.avrcpControllerAbsVolChangedNotification()) {
                        return;
                    }
                    debug("onAbsoluteVolumeChanged: absVol=" + absVol);
                    sendMessage(MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION, absVol);
                }
            };

    protected void broadcastConnectionStateChanged(int currentState) {
        if (mMostRecentState == currentState) {
            return;
        }

        mAdapterService.updateProfileConnectionAdapterProperties(
                mDevice, BluetoothProfile.AVRCP_CONTROLLER, currentState, mMostRecentState);

        debug("Connection state : " + mMostRecentState + "->" + currentState);
        mMostRecentState = currentState;
    }

    private boolean shouldRequestFocus() {
        return mService.getResources()
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
    }

    private void debug(String message) {
        Log.d(TAG, "[" + mDevice + "]: " + message);
    }

    private void warn(String message) {
        Log.w(TAG, "[" + mDevice + "]: " + message);
    }

    private void error(String message) {
        Log.e(TAG, "[" + mDevice + "]: " + message);
    }

    private static String eventToString(int event) {
        return switch (event) {
            case CONNECT -> "CONNECT";
            case DISCONNECT -> "DISCONNECT";
            case ACTIVE_DEVICE_CHANGE -> "ACTIVE_DEVICE_CHANGE";
            case AUDIO_FOCUS_STATE_CHANGE -> "AUDIO_FOCUS_STATE_CHANGE";
            case CLEANUP -> "CLEANUP";
            case CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            case STACK_EVENT -> "STACK_EVENT";
            case MESSAGE_INTERNAL_CMD_TIMEOUT -> "MESSAGE_INTERNAL_CMD_TIMEOUT";
            case MESSAGE_PROCESS_RECEIVED_REMOTE_FEATURES ->
                    "MESSAGE_PROCESS_RECEIVED_REMOTE_FEATURES";
            case MESSAGE_PROCESS_SET_ABS_VOL_CMD -> "MESSAGE_PROCESS_SET_ABS_VOL_CMD";
            case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION ->
                    "MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION";
            case MESSAGE_PROCESS_TRACK_CHANGED -> "MESSAGE_PROCESS_TRACK_CHANGED";
            case MESSAGE_PROCESS_PLAY_POS_CHANGED -> "MESSAGE_PROCESS_PLAY_POS_CHANGED";
            case MESSAGE_PROCESS_PLAY_STATUS_CHANGED -> "MESSAGE_PROCESS_PLAY_STATUS_CHANGED";
            case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION ->
                    "MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION";
            case MESSAGE_PROCESS_GET_FOLDER_ITEMS -> "MESSAGE_PROCESS_GET_FOLDER_ITEMS";
            case MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE ->
                    "MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE";
            case MESSAGE_PROCESS_GET_PLAYER_ITEMS -> "MESSAGE_PROCESS_GET_PLAYER_ITEMS";
            case MESSAGE_PROCESS_FOLDER_PATH -> "MESSAGE_PROCESS_FOLDER_PATH";
            case MESSAGE_PROCESS_SET_BROWSED_PLAYER -> "MESSAGE_PROCESS_SET_BROWSED_PLAYER";
            case MESSAGE_PROCESS_SET_ADDRESSED_PLAYER -> "MESSAGE_PROCESS_SET_ADDRESSED_PLAYER";
            case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED ->
                    "MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED";
            case MESSAGE_PROCESS_NOW_PLAYING_CONTENTS_CHANGED ->
                    "MESSAGE_PROCESS_NOW_PLAYING_CONTENTS_CHANGED";
            case MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS ->
                    "MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS";
            case MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS ->
                    "MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS";
            case MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED ->
                    "MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED";
            case MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM -> "MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM";
            case MESSAGE_GET_FOLDER_ITEMS -> "MESSAGE_GET_FOLDER_ITEMS";
            case MESSAGE_PLAY_ITEM -> "MESSAGE_PLAY_ITEM";
            case MSG_AVRCP_PASSTHRU -> "MSG_AVRCP_PASSTHRU";
            case MSG_AVRCP_SET_SHUFFLE -> "MSG_AVRCP_SET_SHUFFLE";
            case MSG_AVRCP_SET_REPEAT -> "MSG_AVRCP_SET_REPEAT";
            case MESSAGE_PROCESS_IMAGE_DOWNLOADED -> "MESSAGE_PROCESS_IMAGE_DOWNLOADED";
            default -> "UNKNOWN_EVENT_ID_" + event;
        };
    }

    /*
     * AVRCP Controller's MediaSource implementation for a given device
     *
     * Defines how to share metadata, and do playback controls and browsing. Code will call
     * setMetadata() and setPlaybackStatus() to update state. It can also call
     * onBrowseNodeChanged(id) to notify of a recently downloaded or recently changed node value.
     * All other incoming events have functions which can be overrided to handle them, such as
     * play(), onBrowseRequest(), etc.
     */
    private class AvrcpControllerMediaSource extends MediaSource {

        private AvrcpControllerMediaSource() {
            super(mDevice, MediaSource.Protocol.AVRCP_CONTROLLER);
        }

        // Metadata

        private void setMetadata(AvrcpItem track) {
            setMetadata(toMetadata(track));
        }

        private void setPlaybackStatus(PlaybackStateCompat state, int shuffleMode, int repeatMode) {
            setPlaybackStatus(toPlaybackStatus(state, shuffleMode, repeatMode));
        }

        private void setNowPlayingQueue(List<MediaItem> nowPlayingList) {
            if (nowPlayingList == null) {
                setNowPlayingList(null);
                return;
            }

            List<Metadata> metadataList = new ArrayList<>();
            for (MediaItem track : nowPlayingList) {
                metadataList.add(toMetadata(track));
            }

            setNowPlayingList(metadataList);
        }

        // Playback Controls

        @Override
        public void onPrepare() {
            debug("onPrepare() -> Nothing to do");
        }

        @Override
        public void onPlay() {
            debug("onPlay()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PLAY);
        }

        @Override
        public void onPause() {
            debug("onPause()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
        }

        @Override
        public void onSkipToNext() {
            debug("onSkipToNext()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD);
        }

        @Override
        public void onSkipToPrevious() {
            debug("onSkipToPrevious()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD);
        }

        @Override
        public void onSkipToQueueItem(long id) {
            debug("onSkipToQueueItem()");
            onPrepare();
            BrowseTree.BrowseNode node = mBrowseTree.getTrackFromNowPlayingList((int) id);
            if (node != null) {
                sendMessage(MESSAGE_PLAY_ITEM, node);
            }
        }

        @Override
        public void onStop() {
            debug("onStop()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_STOP);
        }

        @Override
        public void onRewind() {
            debug("onRewind()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_REWIND);
        }

        @Override
        public void onFastForward() {
            debug("onFastForward()");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FF);
        }

        @Override
        public void onPlayFromMediaId(String mediaId) {
            debug("onPlayFromMediaId(mediaId=" + mediaId + ")");
            // Play the item if possible.
            onPrepare();
            BrowseTree.BrowseNode node = mBrowseTree.findBrowseNodeByID(mediaId);
            if (node != null) {
                // node was found on this bluetooth device
                sendMessage(MESSAGE_PLAY_ITEM, node);
            } else {
                warn("onPlayFromMediaId(mediaId=" + mediaId + "): no node with given media ID");
            }
        }

        @Override
        public void onSetRepeatMode(MediaSource.RepeatMode repeatMode) {
            debug("onSetRepeatMode(repeatMode=" + repeatMode + ")");
            sendMessage(MSG_AVRCP_SET_REPEAT, toRepeatInt(repeatMode));
        }

        @Override
        public void onSetShuffleMode(MediaSource.ShuffleMode shuffleMode) {
            debug("onSetShuffleMode(shuffleMode=" + shuffleMode + ")");
            sendMessage(MSG_AVRCP_SET_SHUFFLE, toShuffleInt(shuffleMode));
        }

        // browsing

        @Override
        public MediaSource.BrowseNode onGetRoot() {
            return toBrowseNode(mBrowseTree.mRootNode);
        }

        @Override
        public BrowseResult onBrowseRequest(BrowseRequest request) {
            debug("onBrowseRequest(request=" + request + ")");

            String parentMediaId = request.getMediaId();
            BrowseTree.BrowseNode requestedNode = findNode(parentMediaId);

            // If we don't find a node in the tree then do not have any way to browse for the
            // contents. Return an empty list instead.
            if (requestedNode == null) {
                warn("onBrowseRequest(id=" + parentMediaId + "): Failed to find node at id");
                return new BrowseResult(new ArrayList(0), BrowseStatus.ERROR_MEDIA_ID_INVALID);
            }

            // We found _something_, and its either cached and ready to send back, or it needs to be
            // downloaded
            debug("onBrowseRequest(id=" + parentMediaId + "): Found node=" + requestedNode);

            List<MediaSource.BrowseNode> contents =
                    toBrowseNodeList(requestedNode.getContentsAsAvrcpItems());

            if (!requestedNode.isCached()) {
                debug("onBrowseRequest(id=" + parentMediaId + "): node download pending");
                requestContents(requestedNode);
                /* Ongoing downloads can have partial results and we want to make sure they get sent
                 * to the client. If a download gets kicked off as a result of this request, the
                 * contents will be null until the first results arrive.
                 */
                return new BrowseResult(contents, BrowseStatus.DOWNLOAD_PENDING);
            }

            debug(
                    ("onBrowseRequest(id=" + parentMediaId + "): return node")
                            + (", contents=" + requestedNode.getContents()));
            return new BrowseResult(contents, BrowseStatus.SUCCESS);
        }

        // Utilities

        private static MediaSource.Metadata toMetadata(AvrcpItem track) {
            if (track == null) {
                return null;
            }

            String title = track.getTitle();
            if (title == null) {
                title = track.getDisplayableName();
            }

            return new MediaSource.Metadata(
                    title,
                    track.getArtistName(),
                    track.getAlbumName(),
                    track.getTrackNumber(),
                    track.getTotalNumberOfTracks(),
                    track.getGenre(),
                    track.getPlayingTime(),
                    track.getCoverArtLocation());
        }

        private static MediaSource.Metadata toMetadata(MediaItem track) {
            if (track == null) {
                return null;
            }

            return new MediaSource.Metadata(
                    track.getDescription().getTitle().toString(),
                    "",
                    "",
                    0,
                    0,
                    "",
                    0,
                    track.getDescription().getIconUri());
        }

        private static MediaSource.PlaybackStatus toPlaybackStatus(
                PlaybackStateCompat playbackState, int shuffleInt, int repeatInt) {
            if (playbackState == null) {
                return null;
            }

            MediaSource.PlaybackState state =
                    switch (playbackState.getState()) {
                        case PlaybackStateCompat.STATE_PLAYING -> MediaSource.PlaybackState.PLAYING;
                        case PlaybackStateCompat.STATE_PAUSED -> MediaSource.PlaybackState.PAUSED;
                        case PlaybackStateCompat.STATE_STOPPED -> MediaSource.PlaybackState.STOPPED;
                        case PlaybackStateCompat.STATE_ERROR -> MediaSource.PlaybackState.ERROR;
                        case PlaybackStateCompat.STATE_FAST_FORWARDING ->
                                MediaSource.PlaybackState.FAST_FORWARDING;
                        case PlaybackStateCompat.STATE_REWINDING ->
                                MediaSource.PlaybackState.REWINDING;
                        case PlaybackStateCompat.STATE_NONE -> MediaSource.PlaybackState.NONE;
                        default -> MediaSource.PlaybackState.UNKNOWN;
                    };

            List<MediaSource.PlayerAction> availableActions = new ArrayList<>();
            long actions = playbackState.getActions();
            if ((actions & PlaybackStateCompat.ACTION_PREPARE) != 0) {
                availableActions.add(PlayerAction.PREPARE);
            }
            if ((actions & PlaybackStateCompat.ACTION_PLAY) != 0) {
                availableActions.add(PlayerAction.PLAY);
            }
            if ((actions & PlaybackStateCompat.ACTION_STOP) != 0) {
                availableActions.add(PlayerAction.STOP);
            }
            if ((actions & PlaybackStateCompat.ACTION_PAUSE) != 0) {
                availableActions.add(PlayerAction.PAUSE);
            }
            if ((actions & PlaybackStateCompat.ACTION_REWIND) != 0) {
                availableActions.add(PlayerAction.REWIND);
            }
            if ((actions & PlaybackStateCompat.ACTION_FAST_FORWARD) != 0) {
                availableActions.add(PlayerAction.FAST_FORWARD);
            }
            if ((actions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0) {
                availableActions.add(PlayerAction.NEXT);
            }
            if ((actions & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0) {
                availableActions.add(PlayerAction.PREVIOUS);
            }
            if ((actions & PlaybackStateCompat.ACTION_SET_REPEAT_MODE) != 0) {
                availableActions.add(PlayerAction.REPEAT);
            }
            if ((actions & PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) != 0) {
                availableActions.add(PlayerAction.SHUFFLE);
            }

            MediaSource.ShuffleMode shuffleMode = toShuffleMode(shuffleInt);
            MediaSource.RepeatMode repeatMode = toRepeatMode(repeatInt);

            return new MediaSource.PlaybackStatus(
                    state,
                    playbackState.getPosition(),
                    playbackState.getPlaybackSpeed(),
                    playbackState.getActiveQueueItemId(),
                    availableActions,
                    shuffleMode,
                    repeatMode);
        }

        private static MediaSource.ShuffleMode toShuffleMode(int mode) {
            return switch (mode) {
                case PlaybackStateCompat.SHUFFLE_MODE_NONE -> MediaSource.ShuffleMode.OFF;
                case PlaybackStateCompat.SHUFFLE_MODE_GROUP -> MediaSource.ShuffleMode.GROUP;
                case PlaybackStateCompat.SHUFFLE_MODE_ALL -> MediaSource.ShuffleMode.ALL;
                default -> MediaSource.ShuffleMode.OFF;
            };
        }

        private static MediaSource.RepeatMode toRepeatMode(int mode) {
            return switch (mode) {
                case PlaybackStateCompat.REPEAT_MODE_NONE -> MediaSource.RepeatMode.OFF;
                case PlaybackStateCompat.REPEAT_MODE_ONE -> MediaSource.RepeatMode.ONE;
                case PlaybackStateCompat.REPEAT_MODE_GROUP -> MediaSource.RepeatMode.GROUP;
                case PlaybackStateCompat.REPEAT_MODE_ALL -> MediaSource.RepeatMode.ALL;
                default -> MediaSource.RepeatMode.OFF;
            };
        }

        private static int toRepeatInt(MediaSource.RepeatMode mode) {
            return switch (mode) {
                case MediaSource.RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE;
                case MediaSource.RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE;
                case MediaSource.RepeatMode.GROUP -> PlaybackStateCompat.REPEAT_MODE_GROUP;
                case MediaSource.RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL;
                default -> PlaybackStateCompat.REPEAT_MODE_NONE;
            };
        }

        private static int toShuffleInt(MediaSource.ShuffleMode mode) {
            return switch (mode) {
                case MediaSource.ShuffleMode.OFF -> PlaybackStateCompat.SHUFFLE_MODE_NONE;
                case MediaSource.ShuffleMode.GROUP -> PlaybackStateCompat.SHUFFLE_MODE_GROUP;
                case MediaSource.ShuffleMode.ALL -> PlaybackStateCompat.SHUFFLE_MODE_ALL;
                default -> PlaybackStateCompat.SHUFFLE_MODE_NONE;
            };
        }

        private static @Nullable List<MediaSource.BrowseNode> toBrowseNodeList(
                List<AvrcpItem> items) {
            if (items == null) {
                return null;
            }

            List<MediaSource.BrowseNode> browseNodes = new ArrayList<>();
            for (AvrcpItem item : items) {
                MediaSource.BrowseNode node = toBrowseNode(item);
                browseNodes.add(node);
            }
            return browseNodes;
        }

        private static MediaSource.BrowseNode toBrowseNode(AvrcpItem item) {
            if (item == null) {
                return null;
            }

            String mediaId = item.getUuid();
            boolean isPlayable = item.isPlayable();
            boolean isBrowsable = item.isBrowsable();
            Metadata metadata = toMetadata(item);

            return new BrowseNode(mediaId, metadata, isPlayable, isBrowsable);
        }

        private static MediaSource.BrowseNode toBrowseNode(BrowseTree.BrowseNode node) {
            if (node == null) {
                return null;
            }

            return toBrowseNode(node.getAvrcpItem());
        }
    }
}
