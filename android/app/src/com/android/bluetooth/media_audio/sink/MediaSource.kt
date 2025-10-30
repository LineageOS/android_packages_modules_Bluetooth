/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.media_audio.sink

import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

// Other code in Media and Car Media uses "MediaSource" as a tag
private const val TAG = "BtMediaSource"

/** Represents a single media-providing source/protocol and its capabilities. */
open class MediaSource(val device: BluetoothDevice, val protocol: Protocol) {

    // ---------------------------------------------------------------------------------------------
    // Source Facade
    // ---------------------------------------------------------------------------------------------

    // Because the source implementations, like AVRCP Controller and MCP Client can run on their own
    // threads, separately from the MediaAudioServer and MediaAudioDevice objects that aggregate
    // them, we need to make sure the reads of the values are thread safe. We will always update an
    // AtomicReference with an immutable value each time things change, so that callers of
    // getMetadata(), getNowPlayingList() and getQueue() can safely get the latest value on any
    // thread, while the source implementation itseld can call to update the value whenever it wants

    /* The current song metadata of this MediaSource (i.e. track, album, artist) */
    private var metadata = AtomicReference<Metadata?>(null)

    /* The current playback status of this MediaSource (i.e. state, position, supported ops) */
    private var playbackStatus = AtomicReference<PlaybackStatus?>(null)

    /* The current song queue of this MediaSource (i.e. now playing list) */
    private var queue = AtomicReference<List<Metadata>>(emptyList())

    // ---------------------------------------------------------------------------------------------
    // Outside Objects for Event Callbacks
    // ---------------------------------------------------------------------------------------------

    /* An option callback to send events back to the MediaSource creator/owner */
    private var callback = AtomicReference<Callback?>(null)

    // ---------------------------------------------------------------------------------------------
    // Data Classes
    // ---------------------------------------------------------------------------------------------

    /* The available protocols for a MediaSource */
    enum class Protocol {
        AVRCP_CONTROLLER,
        MCP_CLIENT,
    }

    // Metadata and Playback Status

    data class Metadata(
        // val uuid: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackNumber: Long,
        val totalNumberOfTracks: Long,
        val genre: String?,
        val duration: Long,
        val imageUri: Uri?,
    )

    enum class PlaybackState {
        // BUFFERING?
        // CONNECTING?
        // SKIPPING_TO_(NEXT|PREVIOUS|QUEUE_ITEM)
        UNKNOWN,
        NONE,
        STOPPED,
        PAUSED,
        PLAYING,
        FAST_FORWARDING,
        REWINDING,
        ERROR,
    }

    enum class PlayerAction {
        PREPARE,
        PLAY,
        PAUSE,
        STOP,
        REWIND,
        FAST_FORWARD,
        NEXT,
        PREVIOUS,
        REPEAT,
        SHUFFLE,
    }

    enum class ShuffleMode {
        OFF,
        GROUP,
        ALL,
    }

    enum class RepeatMode {
        OFF,
        ONE,
        GROUP,
        ALL,
    }

    data class PlaybackStatus(
        val state: PlaybackState,
        val playbackPosition: Long,
        val playbackSpeed: Float,
        val activeQueueId: Long,
        val availableActions: List<PlayerAction>,
        val shuffleMode: ShuffleMode,
        val repeatMode: RepeatMode,
    )

    // Browsing

    data class BrowseNode(
        val mediaId: String,
        val metadata: Metadata?,
        val isPlayable: Boolean,
        val isBrowsable: Boolean,
    )

    enum class BrowseStatus {
        SUCCESS,
        DOWNLOAD_PENDING,
        ERROR_NO_DEVICE_CONNECTED,
        ERROR_BROWSE_NOT_CONNECTED,
        ERROR_BROWSE_NOT_SUPPORTED,
        ERROR_NO_PREFERRED_MEDIA_SOURCE,
        ERROR_MEDIA_ID_INVALID,
        ERROR_NO_SERVICE,
    }

    data class BrowseRequest(val mediaId: String)

    class BrowseResult(val results: List<BrowseNode>?, val status: BrowseStatus) {
        override fun toString(): String {
            return "BrowseResult<status=${status}, metadata=${results?.size ?: -1}>"
        }
    }

    interface Callback {
        fun onMetadataChanged(metadata: Metadata?)

        fun onPlaybackStatusChanged(playbackStatus: PlaybackStatus?)

        fun onNowPlayingListChanged(queue: List<Metadata>?)

        fun onBrowseNodeChanged(id: String)
    }

    // ---------------------------------------------------------------------------------------------
    // Object Public Interfaces
    // ---------------------------------------------------------------------------------------------

    fun registerCallback(callback: Callback) {
        this.callback.set(callback)
    }

    fun unregisterCallback() {
        this.callback.set(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata and Playback Status Interfaces
    // ---------------------------------------------------------------------------------------------

    // To be called by MediaSource managers, to get state and mix it in with other sources

    fun getMetadata(): Metadata? {
        return metadata.get()
    }

    fun getPlaybackStatus(): PlaybackStatus? {
        return playbackStatus.get()
    }

    fun getNowPlayingList(): List<Metadata> {
        return queue.get()
    }

    // To be called by MediaSource implementers, to send status so above getters work

    fun setMetadata(metadata: Metadata?) {
        logv("setMetadata(metadata=$metadata)")
        this.metadata.set(metadata)
        callback.get()?.onMetadataChanged(metadata)
    }

    fun setPlaybackStatus(playbackStatus: PlaybackStatus?) {
        logv("setPlaybackStatus(playbackStatus=$playbackStatus)")
        this.playbackStatus.set(playbackStatus)
        callback.get()?.onPlaybackStatusChanged(playbackStatus)
    }

    fun setNowPlayingList(queue: List<Metadata>?) {
        logv("setNowPlayingList(queue=$queue)")
        this.queue.set(queue ?: emptyList())
        callback.get()?.onNowPlayingListChanged(queue ?: emptyList())
    }

    // ---------------------------------------------------------------------------------------------
    // Control Interfaces
    // ---------------------------------------------------------------------------------------------

    // To be overridden by MediaSource implementers, to receive playback commands from
    // MediaAudioServer

    open fun onPrepare() {}

    open fun onPlay() {}

    open fun onPause() {}

    open fun onSkipToNext() {}

    open fun onSkipToPrevious() {}

    open fun onSkipToQueueItem(id: Long) {}

    open fun onStop() {}

    open fun onRewind() {}

    open fun onFastForward() {}

    open fun onPlayFromMediaId(id: String) {}

    open fun onSetRepeatMode(mode: RepeatMode) {}

    open fun onSetShuffleMode(mode: ShuffleMode) {}

    // ---------------------------------------------------------------------------------------------
    // Browsing Interfaces
    // ---------------------------------------------------------------------------------------------

    fun getRoot(): BrowseNode? {
        logv("getRoot()")
        return onGetRoot()
    }

    fun browse(request: BrowseRequest): BrowseResult {
        logv("browse(request=$request)")
        return onBrowseRequest(request)
    }

    // To be overridden by MediaSource implementers, to receive browse requests

    open fun onGetRoot(): BrowseNode? {
        return null
    }

    open fun onBrowseRequest(request: BrowseRequest): BrowseResult {
        return BrowseResult(null, BrowseStatus.ERROR_BROWSE_NOT_SUPPORTED)
    }

    // To be called by MediaSource implementers, to notify of changes and results

    fun onBrowseNodeChanged(id: String) {
        logv("onBrowseNodeChanged(id=$id)")
        callback.get()?.onBrowseNodeChanged(id)
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    fun logv(msg: String) {
        Log.v(TAG, "[$device, $protocol] $msg")
    }

    override fun toString(): String {
        return "<MediaSource protocol=$protocol>"
    }

    fun dump(): String {
        return "<MediaSource" +
            " protocol=${protocol}" +
            " metadata=${getMetadata()}" +
            " playback_status=${getPlaybackStatus()}" +
            ">"
    }
}
