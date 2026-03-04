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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "MediaAudioDevice"

/**
 * An abstraction for a connected device, which can have multiple protocols for media and audio.
 *
 * This object manages all registered media and audio sources for a device and presents them as a
 * single device, agnostic of the various available media and audio protocols.
 *
 * A source is considered active if it's the single source among all sources that's allowed to
 * playback to the underlying device's chosen audio output device.
 *
 * The media source picked to represent the overall state of media for the remote device is
 * considered the "Preferred Media Source." This source is used to fill in the current metadata,
 * playback state, supported actions, now playing list, as well as to fulfill browse requests.
 *
 * The audio source picked to represent the overall state of audio for the remote device is
 * considered the "Preferred Audio Source." This source is the one used to determine the audio
 * stream state for a device, which is used on audio focus decisions.
 *
 * Source events and outside requests are serialized to a single handler thread and handled in the
 * order received. In particular, the source list is thread-safe to edit, guarded with locks, making
 * functions that require it and edit it also thread-safe.
 */
class MediaAudioDevice(val device: BluetoothDevice, private val callback: Callback) {

    // ---------------------------------------------------------------------------------------------
    // Immutable Device Info
    // ---------------------------------------------------------------------------------------------

    /* The unique media ID of this device, used in the browse tree. Is different each connection */
    val deviceId: String = "__DEVICE__#${device.address}#${UUID.randomUUID()}"

    // ---------------------------------------------------------------------------------------------
    // Device Media-Audio Facade
    // ---------------------------------------------------------------------------------------------

    /* True if this device is currently the device allowed to make noise */
    private var isActiveSource: Boolean = false

    /* The preferred media source associated with this device. This is the source that's allowed to
     * contribute to the overall media metadata state presented to the Media framework.
     */
    private var preferredMediaSource: MediaSource? = null

    /* The preferred audio source associated with this device. This is the source that's allowed to
     * contribute to the overall audio state used to make focus and other decisions.
     */
    private var preferredAudioSource: AudioSource? = null

    /* The list of pending browse requests for the current preferred MediaSource. If a source is
     * removed while preferred, or the preferred source is switched, we can invalidate any pending
     * requests so clients don't think they're waiting on them any longer. Otherwise, because we
     * don't propagate onBrowseNodeChanged() events from non-preferred sources, a request -> switch
     * -> response could cause the response to be dropped, keeping the client's in a detached or
     * waiting state
     */
    private val pendingBrowseRequests = ConcurrentHashMap<String, MediaSource.BrowseRequest>()

    /* The list of available media sources registered for this device */
    private val mediaSources = ConcurrentHashMap<MediaSource.Protocol, MediaSource>()

    /* The list of available audio sources registered for this device */
    private val audioSources = ConcurrentHashMap<AudioSource.Protocol, AudioSource>()

    /* Single thread executor to serialize all state changes and operations. */
    private val executor = Executors.newSingleThreadExecutor()

    /* The Callback interface, containing the list of events an object owner can receive */
    interface Callback {
        fun onMetadataChanged(metadata: MediaSource.Metadata?) {}

        fun onPlaybackStatusChanged(playbackStatus: MediaSource.PlaybackStatus?) {}

        fun onNowPlayingListChanged(queue: List<MediaSource.Metadata>?) {}

        fun onBrowseNodeChanged(id: String) {}

        fun onAudioStreamStateChanged(state: AudioSource.StreamState?) {}
    }

    // ---------------------------------------------------------------------------------------------
    // Sources List
    // ---------------------------------------------------------------------------------------------

    fun getMediaProtocols(): List<MediaSource.Protocol> = postSourceOperationBlocking {
        mediaSources.keys.toList()
    }

    fun getAudioProtocols(): List<AudioSource.Protocol> = postSourceOperationBlocking {
        audioSources.keys.toList()
    }

    fun addMediaSource(source: MediaSource) {
        postSourceOperationBlocking {
            Log.i(TAG, "[$device] addMediaSource(source=$source)")
            if (!mediaSources.contains(source)) {
                mediaSources[source.protocol] = source
                source.registerCallback(MediaSourceCallback(source))
                updatePreferredMediaSource()
                // If we're not the preferred source or our device is not active, then we should be
                // in the paused state
                if (
                    (source != getPreferredMediaSource() || !isActiveSource()) &&
                        source.getPlaybackStatus()?.state == MediaSource.PlaybackState.PLAYING
                ) {
                    Log.i(
                        TAG,
                        "[$device] addMediaSource(source=$source): Source not active/preferred, pausing",
                    )
                    source.onPause()
                }
            } else {
                Log.w(TAG, "[$device] addMediaSource(source=$source): Source already registered")
            }
        }
    }

    fun addAudioSource(source: AudioSource) {
        postSourceOperationBlocking {
            Log.i(TAG, "[$device] addAudioSource(source=$source)")
            if (!audioSources.contains(source)) {
                audioSources[source.protocol] = source
                source.registerCallback(AudioSourceCallback(source))
                updatePreferredAudioSource()
            } else {
                Log.w(TAG, "[$device] addAudioSource(source=$source): Source already registered")
            }
        }
    }

    fun removeMediaSource(source: MediaSource) {
        postSourceOperationBlocking {
            Log.i(TAG, "[$device] removeMediaSource(source=$source)")
            if (mediaSources.contains(source)) {
                mediaSources.remove(source.protocol)
                source.unregisterCallback()
                updatePreferredMediaSource()
            } else {
                Log.w(TAG, "[$device] removeMediaSource(source=$source): No record of source")
            }
        }
    }

    fun removeAudioSource(source: AudioSource) {
        postSourceOperationBlocking {
            Log.i(TAG, "[$device] removeAudioSource(source=$source)")
            if (audioSources.contains(source)) {
                // If we're active and removing the preferred source, then release the source. Let
                // the preferred source update choose the next source and prepare() it if needed
                if (isActiveSource() && source == getPreferredAudioSource()) {
                    Log.w(
                        TAG,
                        "[$device] removeAudioSource(source=$source): source was active/preferred, releasing",
                    )
                    source.release()
                }
                audioSources.remove(source.protocol)
                source.unregisterCallback()
                updatePreferredAudioSource()
            } else {
                Log.w(TAG, "[$device] removeAudioSource(source=$source): No record of source")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Preferred Source Management
    // ---------------------------------------------------------------------------------------------

    fun getPreferredMediaSource(): MediaSource? {
        return preferredMediaSource
    }

    private fun updatePreferredMediaSource() {
        var changed = false
        if (preferredMediaSource != null && !mediaSources.containsValue(preferredMediaSource)) {
            Log.v(TAG, "[$device] updatePreferredMediaSource(): -> null")
            preferredMediaSource = null
            for (requestMediaId in pendingBrowseRequests.keys) {
                Log.d(
                    TAG,
                    "[$device] updatePreferredMediaSource(): invalidate pending request=$requestMediaId",
                )
                callback.onBrowseNodeChanged(requestMediaId)
            }
            pendingBrowseRequests.clear()
            changed = true
        }

        if (preferredMediaSource == null && mediaSources.isNotEmpty()) {
            // TODO: When supporting more than one protocol in the future, we will need to go from
            // an existing, connected protocol, to the most desirable protocol if that protocol
            // connects late (i.e. LE >  Classic, when Classic connects, _then_ LE, use LE).
            preferredMediaSource = mediaSources.values.firstOrNull()
            Log.i(TAG, "[$device] updatePreferredMediaSource() -> $preferredMediaSource")

            // Update the server with the new active metadata for this device. Invalidate our tree
            preferredMediaSource?.let { currentSource ->
                callback.onMetadataChanged(currentSource.getMetadata())
                callback.onPlaybackStatusChanged(currentSource.getPlaybackStatus())
                callback.onNowPlayingListChanged(currentSource.getNowPlayingList())
            }
            changed = true
        }

        // If our source has changed then our entire tree under the device node has potentially
        // changed. Always notify so clients can refetch.
        if (changed) {
            callback.onBrowseNodeChanged(deviceId)
        } else {
            Log.v(TAG, "[$device] updatePreferredMediaSource(): Still using $preferredMediaSource")
        }
    }

    fun getPreferredAudioSource(): AudioSource? {
        return preferredAudioSource
    }

    private fun updatePreferredAudioSource() {
        if (preferredAudioSource != null && !audioSources.containsValue(preferredAudioSource)) {
            preferredAudioSource = null
        }

        if (preferredAudioSource == null && audioSources.isNotEmpty()) {
            // TODO: When supporting more than one protocol in the future, we will need to go from
            // an existing, connected protocol, to the most desirable protocol if that protocol
            // connects late (i.e. LE >  Classic, when Classic connects, _then_ LE, use LE). Make
            // sure to release any stream resources from the old protocol when switching.
            preferredAudioSource = audioSources.values.firstOrNull()
            Log.i(TAG, "[$device] updatePreferredAudioSource() -> $preferredAudioSource")

            // Update the server with the new active audio state for this device
            preferredAudioSource?.let { currentSource ->
                // If we're the active device, then we can prepare the audio source now
                if (isActiveSource()) {
                    Log.i(
                        TAG,
                        "[$device] updatePreferredAudioSource(): source updated while active, preparing",
                    )
                    currentSource.prepare()
                }

                callback.onAudioStreamStateChanged(currentSource.getStreamState())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Active Source/Device Management
    // ---------------------------------------------------------------------------------------------

    fun isActiveSource(): Boolean {
        return isActiveSource
    }

    fun setActiveSource(isActive: Boolean) {
        // TODO: A2DP Sink has an underlying track object shared amongst all device, which, until
        // refactored, means we need to always call release() before preparing a new device so one
        // device doesn't step on the other device, and this call needs to be blocking so there's no
        // timing issues. Ideally, each A2DP Sink device would have a different track and be able to
        // operate separately from the other devices, so this stepping on each other doesn't happen.
        // If this happens, we can make this non-blocking
        postSourceOperationBlocking {
            setActiveForMedia(isActive)
            setActiveForAudio(isActive)
            this.isActiveSource = isActive
        }
    }

    private fun setActiveForMedia(isActive: Boolean) {
        val isCurrentlyActive = this.isActiveSource
        if (isActive == isCurrentlyActive) {
            Log.d(TAG, "[$device] setActiveForMedia(isActive=$isActive): active state unchanged")
            return
        }

        val preferredSource = preferredMediaSource
        if (preferredSource == null) {
            Log.d(
                TAG,
                "[$device] setActiveForMedia(isActive=$isActive): no preferred source available",
            )
            return
        }

        Log.i(TAG, "[$device] setActiveForMedia(isActive=$isActive)")
        if (
            !isActive &&
                preferredSource.getPlaybackStatus()?.state == MediaSource.PlaybackState.PLAYING
        ) {
            Log.w(TAG, "[$device] setActiveForMedia(isActive=$isActive): pausing playing source")
            preferredSource.onPause()
        }
    }

    private fun setActiveForAudio(isActive: Boolean) {
        val isCurrentlyActive = this.isActiveSource
        if (isActive == isCurrentlyActive) {
            Log.i(TAG, "[$device] setActiveForAudio(isActive=$isActive): active state unchanged")
            return
        }

        val preferredSource = preferredAudioSource
        if (preferredSource == null) {
            Log.i(
                TAG,
                "[$device] setActiveForAudio(isActive=$isActive): no preferred source available",
            )
            return
        }

        Log.i(TAG, "[$device] setActiveForAudio(isActive=$isActive)")
        if (isActive) {
            Log.i(TAG, "[$device] setActiveForAudio(isActive=$isActive): preparing source")
            preferredSource.prepare()
        } else {
            Log.i(TAG, "[$device] setActiveForAudio(isActive=$isActive): releasing source")
            preferredSource.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata and Playback State
    // ---------------------------------------------------------------------------------------------

    fun getMetadata(): MediaSource.Metadata? {
        Log.v(TAG, "[$device] getMetadata()")
        val source = getPreferredMediaSource()
        if (source == null) {
            Log.w(TAG, "[$device] getMetadata(): not preferred media source")
            return null
        }
        return source.getMetadata()
    }

    fun getPlaybackStatus(): MediaSource.PlaybackStatus? {
        Log.v(TAG, "[$device] getPlaybackStatus()")
        val source = getPreferredMediaSource()
        if (source == null) {
            Log.w(TAG, "[$device] getPlaybackStatus(): not preferred media source")
            return null
        }
        return source.getPlaybackStatus()
    }

    fun getNowPlayingList(): List<MediaSource.Metadata> {
        Log.v(TAG, "[$device] getNowPlayingList()")
        val source = getPreferredMediaSource()
        if (source == null) {
            Log.w(TAG, "[$device] getNowPlayingList(): not preferred media source")
            return emptyList()
        }
        return source.getNowPlayingList()
    }

    // ---------------------------------------------------------------------------------------------
    // Playback Controls
    // ---------------------------------------------------------------------------------------------

    fun prepare() {
        postSourceOperation {
            Log.i(TAG, "[$device] prepare()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] prepare(): no preferred media source")
            } else {
                source.onPrepare()
            }
        }
    }

    fun play() {
        postSourceOperation {
            Log.i(TAG, "[$device] play()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] play(): no preferred media source")
            } else {
                source.onPlay()
            }
        }
    }

    fun pause() {
        postSourceOperation {
            Log.i(TAG, "[$device] pause()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] pause(): no preferred media source")
            } else {
                source.onPause()
            }
        }
    }

    fun skipToNext() {
        postSourceOperation {
            Log.i(TAG, "[$device] skipToNext()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] skipToNext(): no preferred media source")
            } else {
                source.onSkipToNext()
            }
        }
    }

    fun skipToPrevious() {
        postSourceOperation {
            Log.i(TAG, "[$device] skipToPrevious()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] skipToPrevious(): no preferred media source")
            } else {
                source.onSkipToPrevious()
            }
        }
    }

    fun skipToQueueItem(id: Long) {
        postSourceOperation {
            Log.i(TAG, "[$device] skipToQueueItem(id=$id)")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] skipToQueueItem(): no preferred media source")
            } else {
                source.onSkipToQueueItem(id)
            }
        }
    }

    fun stop() {
        postSourceOperation {
            Log.i(TAG, "[$device] stop()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] stop(): no preferred media source")
            } else {
                source.onStop()
            }
        }
    }

    fun rewind() {
        postSourceOperation {
            Log.i(TAG, "[$device] rewind()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] rewind(): no preferred media source")
            } else {
                source.onRewind()
            }
        }
    }

    fun fastForward() {
        postSourceOperation {
            Log.i(TAG, "[$device] fastForward()")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] fastForward(): no preferred media source")
            } else {
                source.onFastForward()
            }
        }
    }

    fun playFromMediaId(id: String) {
        postSourceOperation {
            Log.i(TAG, "[$device] playFromMediaId(id=$id)")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] playFromMediaId(): no preferred media source")
            } else {
                source.onPlayFromMediaId(id)
            }
        }
    }

    fun setRepeatMode(mode: MediaSource.RepeatMode) {
        postSourceOperation {
            Log.i(TAG, "[$device] setRepeatMode(mode=$mode)")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] setRepeatMode(): no preferred media source")
            } else {
                source.onSetRepeatMode(mode)
            }
        }
    }

    fun setShuffleMode(mode: MediaSource.ShuffleMode) {
        postSourceOperation {
            Log.i(TAG, "[$device] setShuffleMode(mode=$mode)")
            val source = preferredMediaSource
            if (source == null) {
                Log.w(TAG, "[$device] setShuffleMode(): no preferred media source")
            } else {
                source.onSetShuffleMode(mode)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------------

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun getDeviceRoot(): MediaSource.BrowseNode {
        Log.i(TAG, "[$device] getDeviceRoot() -> $deviceId")
        val mediaId = deviceId
        val deviceName = device.name
        val metadata =
            MediaSource.Metadata(
                title = deviceName,
                artist = null,
                album = null,
                trackNumber = 0,
                totalNumberOfTracks = 0,
                genre = null,
                duration = 0,
                imageUri = null,
            )

        return MediaSource.BrowseNode(
            mediaId = mediaId,
            metadata = metadata,
            isPlayable = false,
            isBrowsable = true,
        )
    }

    fun browse(request: MediaSource.BrowseRequest): MediaSource.BrowseResult {
        return postSourceOperationBlocking {
            Log.i(TAG, "[$device] browse(request=$request)")

            // All browse requests go to the preferred source
            val source = getPreferredMediaSource()
            if (source == null) {
                Log.w(TAG, "[$device] browse(): no preferred media source")

                /* return */ MediaSource.BrowseResult(
                    null,
                    MediaSource.BrowseStatus.ERROR_NO_PREFERRED_MEDIA_SOURCE,
                )
            } else if (deviceId == request.mediaId) {
                // First, check if we were asked for the device root contents. If so, translate to
                // the preferred source's root and request contents, then translate back
                val sourceRoot = source.getRoot()
                if (sourceRoot == null) {
                    Log.w(TAG, "[$device] browse(request=$request): Preferred source has no root")

                    /* return */ MediaSource.BrowseResult(
                        null,
                        MediaSource.BrowseStatus.ERROR_BROWSE_NOT_SUPPORTED,
                    )
                } else {
                    val rootRequest = MediaSource.BrowseRequest(sourceRoot.mediaId)
                    Log.i(
                        TAG,
                        "[$device] browse(request=$request):" +
                            " Device root requested, using protocol root=$sourceRoot",
                    )

                    /* return */ source.browse(rootRequest)
                }
            } else {
                // Otherwise, pass the request to the preferred source and return the result
                val result = source.browse(request)
                if (result.status == MediaSource.BrowseStatus.DOWNLOAD_PENDING) {
                    Log.d(TAG, "[$device] browse(request=$request): request added as pending")
                    pendingBrowseRequests.put(request.mediaId, request)
                } else {
                    // Remove if it was previously pending, no-op otherwise
                    Log.d(TAG, "[$device] browse(request=$request): request removed as pending")
                    pendingBrowseRequests.remove(request.mediaId)
                }
                /* return */ result
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Media Source Events
    // ---------------------------------------------------------------------------------------------

    private inner class MediaSourceCallback(private val mediaSource: MediaSource) :
        MediaSource.Callback {

        override fun onMetadataChanged(metadata: MediaSource.Metadata?) {
            postSourceOperation {
                if (mediaSource == preferredMediaSource) {
                    Log.v(TAG, "[$device] [$mediaSource] onMetadataChanged(metadata=$metadata)")
                    callback.onMetadataChanged(metadata)
                } else {
                    Log.v(
                        TAG,
                        "[$device] [$mediaSource] onMetadataChanged(metadata=$metadata):" +
                            " non-preferred source",
                    )
                }
            }
        }

        override fun onPlaybackStatusChanged(playbackStatus: MediaSource.PlaybackStatus?) {
            postSourceOperation {
                if (mediaSource == preferredMediaSource) {
                    Log.v(
                        TAG,
                        "[$device] [$mediaSource] onPlaybackStatusChanged(status=$playbackStatus)",
                    )
                    callback.onPlaybackStatusChanged(playbackStatus)
                } else {
                    Log.v(
                        TAG,
                        "[$device][$mediaSource] onPlaybackStatusChanged(status=$playbackStatus):" +
                            " non-preferred source",
                    )
                }
            }
        }

        override fun onNowPlayingListChanged(queue: List<MediaSource.Metadata>?) {
            postSourceOperation {
                if (mediaSource == preferredMediaSource) {
                    Log.v(TAG, "[$device] [$mediaSource] onNowPlayingListChanged(queue=$queue)")
                    callback.onNowPlayingListChanged(queue)
                } else {
                    Log.v(
                        TAG,
                        "[$device] [$mediaSource] onNowPlayingListChanged(queue=$queue):" +
                            " non-preferred source",
                    )
                }
            }
        }

        override fun onBrowseNodeChanged(id: String) {
            postSourceOperation {
                if (mediaSource == preferredMediaSource) {
                    Log.d(TAG, "[$device] [$mediaSource] onBrowseNodeChanged(id=$id)")
                    val sourceRootId = mediaSource.getRoot()?.mediaId
                    if (id == sourceRootId) {
                        Log.d(
                            TAG,
                            "[$device] [$mediaSource] onBrowseNodeChanged(id=$id):" +
                                " Preferred protocol's root changed. Swap with our own",
                        )
                        callback.onBrowseNodeChanged(deviceId)
                    } else {
                        callback.onBrowseNodeChanged(id)
                    }
                } else {
                    Log.v(
                        TAG,
                        "[$device] [$mediaSource] onBrowseNodeChanged(id=$id):" +
                            " non-preferred source",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stream Control and Focus
    // ---------------------------------------------------------------------------------------------

    fun getStreamState(): AudioSource.StreamState {
        Log.i(TAG, "[$device] getStreamState()")
        val source = getPreferredAudioSource()
        if (source == null) {
            Log.w(TAG, "[$device] getStreamState(): no preferred audio source")
            return AudioSource.StreamState.UNKNOWN
        }
        return source.getStreamState()
    }

    fun prepareStream() {
        postSourceOperation {
            Log.i(TAG, "[$device] prepare()")
            val source = preferredAudioSource
            if (source == null) {
                Log.w(TAG, "[$device] prepare(): no preferred audio source")
            } else {
                source.prepare()
            }
        }
    }

    fun startStream() {
        postSourceOperation {
            Log.i(TAG, "[$device] start()")
            val source = preferredAudioSource
            if (source == null) {
                Log.w(TAG, "[$device] start(): no preferred audio source")
            } else {
                source.start()
            }
        }
    }

    fun suspendStream() {
        postSourceOperation {
            Log.i(TAG, "[$device] suspend()")
            val source = preferredAudioSource
            if (source == null) {
                Log.w(TAG, "[$device] suspend(): no preferred audio source")
            } else {
                source.suspend()
            }
        }
    }

    fun releaseStream() {
        postSourceOperation {
            Log.i(TAG, "[$device] release()")
            val source = preferredAudioSource
            if (source == null) {
                Log.w(TAG, "[$device] release(): no preferred audio source")
            } else {
                source.release()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Audio Source Events
    // ---------------------------------------------------------------------------------------------

    private inner class AudioSourceCallback(private val audioSource: AudioSource) :
        AudioSource.Callback {

        override fun onStreamStateChanged(state: AudioSource.StreamState) {
            postSourceOperation {
                if (audioSource == preferredAudioSource) {
                    Log.i(TAG, "[$device] [$audioSource] onStreamStateChanged(state=$state)")
                    callback.onAudioStreamStateChanged(state)
                } else {
                    Log.v(
                        TAG,
                        "[$device] [$audioSource] onStreamStateChanged(state=$state): " +
                            "non-preferred source",
                    )
                    // TODO: Pause, stop, request become active, wait until server handles?
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Event Serialization
    // ---------------------------------------------------------------------------------------------

    /**
     * Posts a Runnable to the executor. This is the safe and preferred way to perform any source
     * based state modification or action, ensuring source events and requests to add and remove are
     * all serialized, handled in the order they arrive.
     *
     * @param block The code block to execute while holding the lock on the handler thread.
     */
    private fun postSourceOperation(block: () -> Unit) = executor.submit(block)

    /**
     * Posts a Runnable to the executor. This is the safe and preferred way to perform any source
     * based state modification or action, ensuring source events and requests to add and remove are
     * all serialized, handled in the order they arrive.
     *
     * This variant of the function blocks the calling thread until the Executor has completed
     * running the supplied code block.
     *
     * @param block The code block to execute while holding the lock on the handler thread.
     */
    private fun <T> postSourceOperationBlocking(block: () -> T): T = executor.submit(block).get()

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    override fun toString(): String {
        return "<MediaAudioDevice" +
            " device=$device" +
            " media=${getPreferredMediaSource()}" +
            " audio=${getPreferredAudioSource()}" +
            ">"
    }

    fun dump(): String {
        val sb = StringBuilder()
        sb.append("<MediaAudioDevice $device>")

        sb.append("\n            Device ID: $deviceId")
        sb.append("\n")

        sb.append("\n            Device Audio State:")
        sb.append("\n                stream_state=${getStreamState()}")
        sb.append("\n")

        sb.append("\n            Device Media State:")
        sb.append("\n                metadata=${getMetadata()}")
        sb.append("\n                playback_status=${getPlaybackStatus()}")
        sb.append("\n                queue=${getNowPlayingList()}")

        sb.append("\n\n            Media Sources:")
        for (mediaSource in mediaSources.values) {
            sb.append("\n                ")
            if (mediaSource == getPreferredMediaSource()) {
                sb.append("(Preferred) ")
            }
            sb.append(mediaSource.dump())
        }

        sb.append("\n\n            Audio Sources:")
        for (audioSource in audioSources.values) {
            sb.append("\n                ")
            if (audioSource == getPreferredAudioSource()) {
                sb.append("(Preferred) ")
            }
            sb.append(audioSource.dump())
        }

        return sb.toString()
    }
}
