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

package com.android.bluetooth.avrcp;

import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUtils;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Looper;
import android.os.UserManager;
import android.sysprop.BluetoothProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.audio_util.ListItem;
import com.android.bluetooth.audio_util.MediaData;
import com.android.bluetooth.audio_util.MediaPlayerList;
import com.android.bluetooth.audio_util.MediaPlayerWrapper;
import com.android.bluetooth.audio_util.Metadata;
import com.android.bluetooth.audio_util.PlayStatus;
import com.android.bluetooth.audio_util.PlayerInfo;
import com.android.bluetooth.audio_util.PlayerSettingsManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.ProfileService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.bluetooth.util.Text;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Objects;

/** Provides Bluetooth AVRCP Target profile as a service in the Bluetooth application. */
public class AvrcpTargetService extends ProfileService {
    private static final String TAG = AvrcpTargetService.class.getSimpleName();

    private static final int MEDIA_KEY_EVENT_LOGGER_SIZE = 20;
    private static final String MEDIA_KEY_EVENT_LOGGER_TITLE = "BTAudio Media Key Events";
    private final BluetoothEventLogger mMediaKeyEventLogger =
            new BluetoothEventLogger(MEDIA_KEY_EVENT_LOGGER_SIZE, MEDIA_KEY_EVENT_LOGGER_TITLE);

    private final BroadcastReceiver mUserUnlockedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // EXTRA_USER_HANDLE is sent for ACTION_USER_UNLOCKED
                    // (even if the documentation doesn't mention it)
                    final int userId =
                            intent.getIntExtra(
                                    Intent.EXTRA_USER_HANDLE,
                                    BluetoothUtils.USER_HANDLE_NULL.getIdentifier());
                    if (userId == BluetoothUtils.USER_HANDLE_NULL.getIdentifier()) {
                        Log.e(TAG, "userChangeReceiver received an invalid EXTRA_USER_HANDLE");
                        return;
                    }
                    mMediaPlayerList.init(new ListCallback());
                }
            };

    // Cover Art Service (Storage + BIP Server)
    private final AvrcpCoverArtService mAvrcpCoverArtService;
    private final AvrcpVersion mAvrcpVersion;
    private final MediaPlayerList mMediaPlayerList;
    private final PlayerSettingsManager mPlayerSettingsManager;
    private final AudioManager mAudioManager;
    private final AvrcpBroadcastReceiver mReceiver;
    private final AvrcpNativeInterface mNativeInterface;
    private final AvrcpVolumeManager mVolumeManager;
    private final boolean mIsVfsCoverArtEnabled;

    // Only used to see if the metadata has changed from its previous value
    private MediaData mCurrentData;

    public AvrcpTargetService(
            AdapterService adapterService,
            BluetoothStorageManager storage,
            UserManager userManager) {
        this(
                requireNonNull(adapterService),
                storage,
                null,
                null,
                null,
                userManager,
                Looper.myLooper());
    }

    @VisibleForTesting
    AvrcpTargetService(
            AdapterService adapterService,
            BluetoothStorageManager storage,
            AudioManager audioManager,
            AvrcpNativeInterface nativeInterface,
            AvrcpVolumeManager volumeManager,
            UserManager userManager,
            Looper looper) {
        super(BluetoothProfile.AVRCP, adapterService);
        mAudioManager =
                requireNonNullElseGet(audioManager, () -> obtainSystemService(AudioManager.class));
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new AvrcpNativeInterface(adapterService, this));

        mMediaPlayerList = new MediaPlayerList(adapterService, looper);

        IntentFilter userFilter = new IntentFilter();
        userFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        getApplicationContext().registerReceiver(mUserUnlockedReceiver, userFilter);

        Log.i(TAG, "Starting the AVRCP Target Service");
        mCurrentData = new MediaData(null, null, null);

        mPlayerSettingsManager = new PlayerSettingsManager(mMediaPlayerList, this);
        mNativeInterface.init();

        mAvrcpVersion = AvrcpVersion.getCurrentSystemPropertiesValue();
        mVolumeManager =
                requireNonNullElseGet(
                        volumeManager,
                        () -> new AvrcpVolumeManager(adapterService, storage, mNativeInterface));

        if (userManager.isUserUnlocked()) {
            mMediaPlayerList.init(new ListCallback());
        }

        if (!mAvrcpVersion.isAtLeastVersion(AvrcpVersion.AVRCP_VERSION_1_6)) {
            Log.e(TAG, "Please use AVRCP version 1.6 to enable cover art");
            mAvrcpCoverArtService = null;
        } else {
            AvrcpCoverArtService coverArtService =
                    new AvrcpCoverArtService(adapterService, mNativeInterface);
            if (coverArtService.start()) {
                mAvrcpCoverArtService = coverArtService;
            } else {
                Log.e(TAG, "Failed to start cover art service");
                mAvrcpCoverArtService = null;
            }
        }

        mIsVfsCoverArtEnabled = mMediaPlayerList.isVfsCoverArtEnabled();

        mReceiver = new AvrcpBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(AudioManager.ACTION_VOLUME_CHANGED);
        registerReceiver(mReceiver, filter);
    }

    /** Checks for profile enabled state in Bluetooth sysprops. */
    public static boolean isEnabled() {
        return BluetoothProperties.isProfileAvrcpTargetEnabled().orElse(false);
    }

    /** Callbacks from {@link MediaPlayerList} to update the MediaData and folder updates. */
    class ListCallback implements MediaPlayerList.MediaUpdateCallback {
        @Override
        public void run(MediaData data) {
            boolean metadata = !Objects.equals(mCurrentData.metadata, data.metadata);
            boolean state = !MediaPlayerWrapper.playstateEquals(mCurrentData.state, data.state);
            boolean queue = isQueueUpdated(mCurrentData.queue, data.queue);

            Log.d(
                    TAG,
                    "onMediaUpdated: track_changed="
                            + metadata
                            + " state="
                            + state
                            + " queue="
                            + queue);
            mCurrentData = data;

            // Only send an update when one of the states was updated.
            if (metadata || state || queue) {
                mNativeInterface.sendMediaUpdate(metadata, state, queue);
            }
        }

        @Override
        public void run(boolean availablePlayers, boolean addressedPlayers, boolean uids) {
            mNativeInterface.sendFolderUpdate(availablePlayers, addressedPlayers, uids);
        }
    }

    /**
     * Listens for {@link AudioManager.ACTION_VOLUME_CHANGED} events to update {@link
     * AvrcpVolumeManager} in case the remote device doesn't support absolute volume.
     */
    private class AvrcpBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!action.equals(AudioManager.ACTION_VOLUME_CHANGED)) {
                return;
            }
            int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
            if (streamType != AudioManager.STREAM_MUSIC) {
                return;
            }
            int volume = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
            BluetoothDevice activeDevice = getA2dpActiveDevice();
            if (activeDevice != null && !mVolumeManager.getAbsoluteVolumeSupported(activeDevice)) {
                Log.d(TAG, "stream volume change to " + volume + " " + activeDevice);
                mVolumeManager.storeVolumeForDevice(activeDevice, volume);
            }
        }
    }

    /** Returns the {@link AvrcpCoverArtService} instance. */
    public AvrcpCoverArtService getCoverArtService() {
        return mAvrcpCoverArtService;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        if (mAvrcpCoverArtService != null) {
            mAvrcpCoverArtService.stop();
        }

        unregisterReceiver(mReceiver);

        // We check the interfaces first since they only get set on User Unlocked
        mPlayerSettingsManager.cleanup();
        mMediaPlayerList.cleanup();
        mNativeInterface.cleanup();
        mVolumeManager.cleanup();
        getApplicationContext().unregisterReceiver(mUserUnlockedReceiver);
    }

    /** Returns the active A2DP {@link BluetoothDevice} */
    private BluetoothDevice getA2dpActiveDevice() {
        return getAdapterService().getA2dpService().map(A2dpService::getActiveDevice).orElse(null);
    }

    /**
     * Sets a {@link BluetoothDevice} as active A2DP device.
     *
     * <p>This will be called by the native stack when a play event is received from a remote
     * device. See packages/modules/Bluetooth/system/profile/avrcp/device.cc.
     */
    private void setA2dpActiveDevice(@NonNull BluetoothDevice device) {
        getAdapterService().setActiveDevice(device, BluetoothAdapter.ACTIVE_DEVICE_AUDIO);
    }

    /** Informs {@link AvrcpVolumeManager} that a new device is connected */
    void deviceConnected(BluetoothDevice device, boolean absoluteVolume) {
        Log.i(TAG, "deviceConnected: device=" + device + " absoluteVolume=" + absoluteVolume);
        mVolumeManager.deviceConnected(device, absoluteVolume);
    }

    /** Informs {@link AvrcpVolumeManager} that a device is disconnected */
    void deviceDisconnected(BluetoothDevice device) {
        Log.i(TAG, "deviceDisconnected: device=" + device);
        mVolumeManager.deviceDisconnected(device);
    }

    /**
     * Returns the remembered volume for a device or -1 if none.
     *
     * <p>See {@link AvrcpVolumeManager}.
     */
    public int getRememberedVolumeForDevice(BluetoothDevice device) {
        if (device == null) return -1;

        return mVolumeManager.getVolume(device, mVolumeManager.getNewDeviceVolume());
    }

    /**
     * Handle when A2DP connection state changes.
     *
     * <p>If the A2DP connection disconnects, we request AVRCP to disconnect device as well.
     */
    public void handleA2dpConnectionStateChanged(BluetoothDevice device, int newState) {
        if (device == null) return;
        if (newState == STATE_DISCONNECTED) {
            // If there is no connection, disconnectDevice() will do nothing
            if (mNativeInterface.disconnectDevice(device)) {
                Log.d(TAG, "request to disconnect device " + device);
            }
        }
    }

    /**
     * Handles active device changes in A2DP.
     *
     * <p>Signals {@link AvrcpVolumeManager} that the current A2DP active device has changed which
     * will then inform {@link AudioManager} about its absolute volume support. If absolute volume
     * is supported, it will also set the volume level on the remote device.
     *
     * <p>Informs all remote devices that there is a play status update.
     */
    public void handleA2dpActiveDeviceChanged(BluetoothDevice device) {
        mVolumeManager.volumeDeviceSwitched(device);
        // Update all the playback status info for each connected device
        mNativeInterface.sendMediaUpdate(false, true, false);
    }

    /** Informs {@link AvrcpVolumeManager} that a remote device requests a volume change */
    void setVolume(int avrcpVolume) {
        BluetoothDevice activeDevice = getA2dpActiveDevice();
        if (activeDevice == null) {
            Log.d(TAG, "setVolume: no active device");
            return;
        }

        mVolumeManager.setVolume(activeDevice, avrcpVolume);
    }

    /**
     * Sends a volume change request to the remote device.
     *
     * <p>Does nothing if the device doesn't support absolute volume.
     *
     * <p>The remote device that will receive the request is the A2DP active device.
     */
    public void sendVolumeChanged(int deviceVolume) {
        BluetoothDevice activeDevice = getA2dpActiveDevice();
        if (activeDevice == null) {
            Log.d(TAG, "sendVolumeChanged: no active device");
            return;
        }

        mVolumeManager.sendVolumeChanged(activeDevice, deviceVolume);
    }

    /**
     * Returns the current song info from the active player in {@link MediaPlayerList}.
     *
     * <p>If a {@link com.android.bluetooth.audio_util.Image} is present in the {@link Metadata},
     * add its handle from {@link AvrcpCoverArtService}.
     */
    Metadata getSongInfo(String mediaId) {
        Metadata metadata = mMediaPlayerList.getSongInfo(mediaId);
        if (mAvrcpCoverArtService != null && metadata.image != null) {
            metadata.image.setImageHandle(mAvrcpCoverArtService.storeImage(metadata.image));
        }
        return metadata;
    }

    /** Returns the current play status of the active player from {@link MediaPlayerList}. */
    PlayStatus getPlayState() {
        return PlayStatus.fromPlaybackState(
                mMediaPlayerList.getCurrentPlayStatus(),
                Long.parseLong(mMediaPlayerList.getSongInfo("").duration));
    }

    /** Returns the current media ID of the active player from {@link MediaPlayerList}. */
    String getCurrentMediaId() {
        String id = mMediaPlayerList.getCurrentMediaId();
        if (id != null && !id.isEmpty()) return id;

        Metadata song = mMediaPlayerList.getSongInfo("");
        if (song != null && !song.mediaId.isEmpty()) return song.mediaId;

        // We always want to return something, the error string just makes debugging easier
        return "error";
    }

    /**
     * Returns the playing queue of the active player from {@link MediaPlayerList}.
     *
     * <p>If a {@link com.android.bluetooth.audio_util.Image} is present in the {@link Metadata} of
     * the queued items, add its handle from {@link AvrcpCoverArtService}.
     */
    List<Metadata> getNowPlayingList() {
        String currentMediaId = getCurrentMediaId();
        Metadata currentTrack = null;
        List<Metadata> nowPlayingList = mMediaPlayerList.getNowPlayingList();
        if (mAvrcpCoverArtService != null) {
            for (Metadata metadata : nowPlayingList) {
                if (TextUtils.equals(metadata.mediaId, currentMediaId)) {
                    currentTrack = metadata;
                } else if (metadata.image != null) {
                    metadata.image.setImageHandle(mAvrcpCoverArtService.storeImage(metadata.image));
                }
            }

            // Always store the current item from the queue last so we know the image is in storage
            if (currentTrack != null && currentTrack.image != null) {
                currentTrack.image.setImageHandle(
                        mAvrcpCoverArtService.storeImage(currentTrack.image));
            }
        }
        return nowPlayingList;
    }

    /**
     * Returns the active browsable player ID from {@link MediaPlayerList}.
     *
     * <p>Note: Currently, only returns the Bluetooth player ID. Browsable players are
     * subdirectories of the Bluetooth player. See {@link MediaPlayerList} class description.
     */
    int getCurrentPlayerId() {
        return mMediaPlayerList.getCurrentPlayerId();
    }

    /**
     * Returns the list of browsable players from {@link MediaPlayerList}.
     *
     * <p>Note: Currently, only returns the Bluetooth player. Browsable players are subdirectories
     * of the Bluetooth player. See {@link MediaPlayerList} class description.
     */
    List<PlayerInfo> getMediaPlayerList() {
        return mMediaPlayerList.getMediaPlayerList();
    }

    /** See {@link MediaPlayerList#setBrowsedPlayer}. */
    void setBrowsedPlayer(
            int playerId, String currentPath, MediaPlayerList.SetBrowsedPlayerCallback cb) {
        mMediaPlayerList.setBrowsedPlayer(playerId, currentPath, cb);
    }

    /** See {@link MediaPlayerList#setAddressedPlayer}. */
    int setAddressedPlayer(int playerId) {
        return mMediaPlayerList.setAddressedPlayer(playerId);
    }

    /** See {@link MediaPlayerList#getFolderItems}. */
    void getFolderItems(int playerId, String mediaId, MediaPlayerList.GetFolderItemsCallback cb) {
        mMediaPlayerList.getFolderItems(
                playerId,
                mediaId,
                (id, results) -> {
                    if (mIsVfsCoverArtEnabled && mAvrcpCoverArtService != null) {
                        for (ListItem item : results) {
                            if (item != null && item.song != null && item.song.image != null) {
                                item.song.image.setImageHandle(
                                        mAvrcpCoverArtService.storeImage(item.song.image));
                            }
                        }
                    }
                    cb.run(id, results);
                });
    }

    /** See {@link MediaPlayerList#playItem}. */
    void playItem(int playerId, boolean nowPlaying, String mediaId) {
        // NOTE: playerId isn't used if nowPlaying is true, since its assumed to be the current
        // active player
        mMediaPlayerList.playItem(playerId, nowPlaying, mediaId);
    }

    /** Informs {@link AudioManager} of an incoming key event from a remote device. */
    void sendMediaKeyEvent(BluetoothDevice device, int key, boolean pushed) {
        MediaPlayerWrapper activePlayer = mMediaPlayerList.getActivePlayer();

        boolean voiceCommunicationActive = isVoiceCommunicationActive();
        int keyCode = AvrcpPassthrough.toKeyCode(key);

        String keyEventLog =
                "sendMediaKeyEvent:"
                        + " device="
                        + device
                        + " key="
                        + KeyEvent.keyCodeToString(keyCode)
                        + " pushed="
                        + pushed
                        + " voice active="
                        + voiceCommunicationActive
                        + " internal active player is "
                        + (activePlayer == null ? null : activePlayer.getPackageName());
        mMediaKeyEventLogger.logd(TAG, keyEventLog);

        // Some devices will send a play event upon SCO disconnection and some will also send a
        // stop event upon SCO connection resulting in music starting even if the call is still
        // ongoing. As this is a BT specific issue we handle it here.
        if (voiceCommunicationActive
                && (KeyEvent.KEYCODE_MEDIA_PLAY == keyCode
                        || KeyEvent.KEYCODE_MEDIA_STOP == keyCode)) {
            Log.w(
                    TAG,
                    "Received "
                            + KeyEvent.keyCodeToString(keyCode)
                            + " event while call is active, not sending it to AudioManager");
            return;
        }

        // A/V controls should be sent to the addressed player.
        // We don't have a way to set a media player as the active session so we
        // keep the active device playing until we receive a PLAY event for the
        // addressed player. Other events will still be broadcasted to active player.
        // Note: some devices will only send the event when the button is released and some
        // will only send the event when the button is pressed, so we can't filter it here.
        MediaPlayerWrapper addressedPlayer = mMediaPlayerList.getAddressedPlayer();
        if (addressedPlayer != null
                && KeyEvent.KEYCODE_MEDIA_PLAY == keyCode
                && activePlayer != addressedPlayer) {
            Log.d(
                    TAG,
                    "Sending play event directly to addressed player: "
                            + addressedPlayer.getPackageName());
            addressedPlayer.playCurrent();
            return;
        }

        Log.d(TAG, "KeyEvent dispatched to AudioManager");

        if (isA2dpConnectionForbidden(device)) {
            Log.e(TAG, "Ignore passthrough key as media profiles disabled");
            return;
        }

        if (KeyEvent.KEYCODE_VOLUME_UP == keyCode || KeyEvent.KEYCODE_VOLUME_DOWN == keyCode) {
            mAudioManager.adjustSuggestedStreamVolume(
                    KeyEvent.KEYCODE_VOLUME_UP == keyCode
                            ? AudioManager.ADJUST_RAISE
                            : AudioManager.ADJUST_LOWER,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.FLAG_SHOW_UI);
            return;
        }

        int action = pushed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        KeyEvent event = new KeyEvent(action, keyCode);
        mAudioManager.dispatchMediaKeyEvent(event);
    }

    /** Returns {@code true} if A2DP connection policy is forbidden. */
    private boolean isA2dpConnectionForbidden(BluetoothDevice device) {
        return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                == getAdapterService()
                        .getA2dpService()
                        .map(a2dp -> a2dp.getConnectionPolicy(device))
                        .orElse(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
    }

    /**
     * Sets a {@link BluetoothDevice} as active A2DP device.
     *
     * <p>This will be called by the native stack when a play event is received from a remote
     * device. See packages/modules/Bluetooth/system/profile/avrcp/device.cc.
     */
    void setActiveDevice(BluetoothDevice device) {
        Log.i(TAG, "setActiveDevice: device=" + device);
        if (device == null) {
            Log.wtf(TAG, "setActiveDevice: could not find device " + device);
            return;
        }
        setA2dpActiveDevice(device);
    }

    /** Called from native to update current active player shuffle mode. */
    boolean setShuffleMode(int shuffleMode) {
        return mPlayerSettingsManager.setPlayerShuffleMode(shuffleMode);
    }

    /** Called from native to update current active player repeat mode. */
    boolean setRepeatMode(int repeatMode) {
        return mPlayerSettingsManager.setPlayerRepeatMode(repeatMode);
    }

    /** Called from native to get the current active player repeat mode. */
    int getRepeatMode() {
        return mPlayerSettingsManager.getPlayerRepeatMode();
    }

    /** Called from native to get the current active player shuffle mode. */
    int getShuffleMode() {
        return mPlayerSettingsManager.getPlayerShuffleMode();
    }

    /** Called from player callback to indicate new settings to remote device. */
    public void sendPlayerSettings(int repeatMode, int shuffleMode) {
        mNativeInterface.sendPlayerSettings(repeatMode, shuffleMode);
    }

    /**
     * Compares the {@link Metadata} of the current and new queues
     *
     * <p>Whenever the current playing track changed in the now playing list, its metadata is
     * updated. We should only send an update if the elements of the queue have been modified.
     *
     * <p>Only Title, Album and Artist metadata can be used for comparison. The metadata ID
     * corresponds to the position in the list and is not unique for each media. Genre, duration and
     * cover art are updated when the playing track changes as we are only able to retrieve this
     * information then.
     */
    @VisibleForTesting
    public static boolean isQueueUpdated(List<Metadata> currentQueue, List<Metadata> newQueue) {
        if (newQueue == null && currentQueue == null) {
            return false;
        }
        if (newQueue == null || currentQueue == null || currentQueue.size() != newQueue.size()) {
            return true;
        }

        for (int index = 0; index < currentQueue.size(); index++) {
            Metadata currentMetadata = currentQueue.get(index);
            Metadata newMetadata = newQueue.get(index);

            if (!Objects.equals(currentMetadata.title, newMetadata.title)
                    || !Objects.equals(currentMetadata.artist, newMetadata.artist)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVoiceCommunicationActive() {
        for (AudioPlaybackConfiguration audioConfig :
                mAudioManager.getActivePlaybackConfigurations()) {
            if (audioConfig.getAudioAttributes().getUsage()
                            == AudioAttributes.USAGE_VOICE_COMMUNICATION
                    && audioConfig.isActive()) {
                Log.d(TAG, "Voice active playback found: " + audioConfig);
                return true;
            }
        }
        return false;
    }

    /** Dump debugging information to the string builder */
    public void dump(StringBuilder sb) {
        super.dump(sb);
        StringBuilder tempBuilder = new StringBuilder();
        tempBuilder.append("AVRCP version: ").append(mAvrcpVersion).append("\n");

        mMediaPlayerList.dump(tempBuilder);

        mMediaKeyEventLogger.dump(tempBuilder);
        tempBuilder.append("\n");
        mVolumeManager.dump(tempBuilder);
        if (mAvrcpCoverArtService != null) {
            tempBuilder.append("\n");
            mAvrcpCoverArtService.dump(tempBuilder);
        }

        // Tab everything over by two spaces
        sb.append(Text.indent(tempBuilder.toString(), "  "));
    }
}
