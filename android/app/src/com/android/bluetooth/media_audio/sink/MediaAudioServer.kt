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
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import com.android.bluetooth.R
import com.android.bluetooth.Util.isIotDevice
import com.android.bluetooth.Util.isTv
import com.android.bluetooth.Util.isXrDevice
import com.android.bluetooth.a2dpsink.A2dpSinkService
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService
import com.android.bluetooth.mcp.McpClientService
import com.android.internal.annotations.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MediaAudioServer"

/**
 * Main service responsible for owning the state of media for Bluetooth. It manages all connected
 * devices and their media/audio protocols, presenting a single, unified MediaSession to the Android
 * system based on the active device and its preferred protocols.
 */
class MediaAudioServer(private val context: Context) {

    /* Represents the device that is allowed to play audio out the speakers. The device mar or may
     * not be actively streaming though.
     */
    private var activeDevice = AtomicReference<MediaAudioDevice?>(null)

    /* The current audio focus state of Bluetooth Media/Audio. We require audio focus to play out
     * the speakers.
     */
    private var audioFocusState = AtomicInteger(AudioManager.AUDIOFOCUS_NONE)

    /* Used with Audio Focus Requests to track the our focus state over time */
    val audioFocusListener = OnAudioFocusChangeListener { focusChange ->
        onAudioFocusStateChanged(focusChange)
    }

    /* Used to play silence so our package is associated with recent playback. This is required
     * by the Audio Framework when using an AAudio source in a different process like we are, so
     * we can still get media keys
     */
    private var mediaPlayer: MediaPlayer? = null

    /* Indicates if we should attempt to restore a "playing" playback state following a transient
     * loss. During a transient focus loss, we will attempt to pause the remote device's media
     * player if they were playing, and re-establish that state when we regain. Any pause or stop
     * commands during our loss will cancel that pending attempt.
     */
    private var shouldSendPlayOnFocusRecovery = false

    /* Used to talk to the Audio Framework for focus requests */
    private val audioManager = context.getSystemService(AudioManager::class.java)

    // A map to store and manage all connected media devices, keyed by a unique device ID.
    private val mediaAudioDevices = ConcurrentHashMap<BluetoothDevice, MediaAudioDevice>()

    /*
     * An internal executor to serialize events and commands for devices.
     *
     * Because devices and their sources can operate on different threads from this server, its
     * important to make sure we always change state and process requests in the order we receive
     * them.
     */
    private val executor = Executors.newSingleThreadExecutor()

    // ---------------------------------------------------------------------------------------------
    // Static Members
    // ---------------------------------------------------------------------------------------------

    companion object {

        // -----------------------------------------------------------------------------------------
        // Singleton Management
        // -----------------------------------------------------------------------------------------

        /** The singleton MediaAudioServer instance reference */
        private var INSTANCE = AtomicReference<MediaAudioServer?>(null)

        /** Set the MediaAudioServer instance */
        private fun setInstance(service: MediaAudioServer?) {
            INSTANCE.set(service)
            Log.i(TAG, "Service set to $service")
        }

        /** Get the MediaAudioServer instance */
        @JvmStatic
        fun getInstance(): MediaAudioServer? {
            return INSTANCE.get()
        }

        // -----------------------------------------------------------------------------------------
        // Enabled/Disabled
        // -----------------------------------------------------------------------------------------

        @JvmStatic
        fun isEnabled(): Boolean {
            return isMediaEnabled() || isAudioEnabled()
        }

        private fun isMediaEnabled(): Boolean {
            return AvrcpControllerService.isEnabled() || McpClientService.isEnabled()
        }

        private fun isAudioEnabled(): Boolean {
            return A2dpSinkService.isEnabled()
        }

        // -----------------------------------------------------------------------------------------
        // Browsing
        // -----------------------------------------------------------------------------------------

        private val BROWSER_ROOT = "__ROOT__"

        @JvmStatic
        fun getRoot(
            clientPackageName: String,
            clientUid: Int?,
            rootHints: Bundle?,
        ): MediaSource.BrowseNode {
            // This root must be accessible statically, even when Bluetooth is off
            return MediaSource.BrowseNode(BROWSER_ROOT, null, false, true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Service Lifecycle
    // ---------------------------------------------------------------------------------------------

    init {
        // Start the media browser service if it's not already up
        if (isMediaEnabled()) {
            val startIntent = Intent(context, BluetoothMediaBrowserService::class.java)
            context.startService(startIntent)
        }
        setInstance(this)
    }

    fun cleanup() {
        // Give back audio focus if we have it
        abandonAudioFocus()

        // stop media browser service
        if (isMediaEnabled()) {
            val stopIntent = Intent(context, BluetoothMediaBrowserService::class.java)
            context.stopService(stopIntent)
        }
        setInstance(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Active Device Management
    // ---------------------------------------------------------------------------------------------

    private fun setActiveDevice(device: MediaAudioDevice?) {
        Log.i(TAG, "setActiveDevice(device=$device)")

        val currentActiveDevice = activeDevice.get()

        if (currentActiveDevice != null) {
            currentActiveDevice.setActiveSource(false)
        }

        activeDevice.set(device)

        if (device != null) {
            device.setActiveSource(true)
            if (getAudioFocusState() == AudioManager.AUDIOFOCUS_GAIN) {
                device.startStream()
            }
            BluetoothMediaBrowserService.onTrackChanged(device.getMetadata())
            BluetoothMediaBrowserService.onPlaybackStateChanged(device.getPlaybackStatus())
            BluetoothMediaBrowserService.onNowPlayingQueueChanged(device.getNowPlayingList())
        } else {
            // TODO(FLags.media_audio_server): The old AVRCP/A2DP code and this new code have
            // similar function names, so this helps the compiler know which functions to use. Pass
            // null directly on flag clean up
            val nullTrack: MediaSource.Metadata? = null
            val nullPlaybackState: MediaSource.PlaybackStatus? = null
            val nullNowPlayingQueue: List<MediaSource.Metadata>? = null
            BluetoothMediaBrowserService.onTrackChanged(nullTrack)
            BluetoothMediaBrowserService.onPlaybackStateChanged(nullPlaybackState)
            BluetoothMediaBrowserService.onNowPlayingQueueChanged(nullNowPlayingQueue)
        }
    }

    fun getActiveDevice(): MediaAudioDevice? {
        return activeDevice.get()
    }

    // ---------------------------------------------------------------------------------------------
    // Media and Audio Source Management
    // ---------------------------------------------------------------------------------------------

    /**
     * Called by external protocol services to register a new media interface for a device. This
     * will create a MediaAudioDevice if it's the first registration for the device.
     *
     * @param device A unique identifier for the hardware device (e.g., MAC address).
     * @param source The protocol that is connecting.
     */
    fun registerMediaSource(source: MediaSource) {
        postDeviceOperationBlocking {
            val device = source.device

            Log.i(TAG, "registerMediaSource(device=$device, source=$source)")

            val size = mediaAudioDevices.size
            val mediaAudioDevice =
                mediaAudioDevices.computeIfAbsent(device) { key ->
                    MediaAudioDevice(key, MediaAudioDeviceCallback(key))
                }
            mediaAudioDevice.addMediaSource(source)

            if (getActiveDevice() == null) {
                setActiveDevice(mediaAudioDevice)
            }

            if (size != mediaAudioDevices.size) {
                Log.i(TAG, "registerMediaSource(device=$device, source=$source): Added new device")
                onRootContentsChanged()
            }
        }
    }

    /**
     * Called by external protocol services to register a new audio interface for a device. This
     * will create a MediaAudioDevice if it's the first registration for the device.
     *
     * @param device A unique identifier for the hardware device (e.g., MAC address).
     * @param source The protocol that is connecting.
     */
    fun registerAudioSource(source: AudioSource) {
        postDeviceOperationBlocking {
            val device = source.device

            Log.i(TAG, "registerAudioSource(device=$device, source=$source)")

            val size = mediaAudioDevices.size
            val mediaAudioDevice =
                mediaAudioDevices.computeIfAbsent(device) { key ->
                    MediaAudioDevice(key, MediaAudioDeviceCallback(key))
                }
            mediaAudioDevice.addAudioSource(source)

            if (getActiveDevice() == null) {
                setActiveDevice(mediaAudioDevice)
            }

            if (size != mediaAudioDevices.size) {
                Log.i(TAG, "registerAudioSource(device=$device, source=$source): Added new device")
                onRootContentsChanged()
            }
        }
    }

    /**
     * Called by external protocol services to unregister a protocol interface for a device. If no
     * protocols remain for a device, the device object is removed.
     *
     * @param device The unique identifier for the hardware device.
     * @param source The protocol that is disconnecting.
     */
    fun unregisterMediaSource(source: MediaSource) {
        postDeviceOperationBlocking {
            val device = source.device

            Log.i(TAG, "unregisterMediaSource(device=$device, source=$source)")

            val size = mediaAudioDevices.size
            val mediaAudioDevice = mediaAudioDevices.get(device)
            if (mediaAudioDevice != null) {
                mediaAudioDevice.removeMediaSource(source)

                if (
                    mediaAudioDevice.getMediaProtocols().isEmpty() &&
                        mediaAudioDevice.getAudioProtocols().isEmpty()
                ) {
                    Log.i(
                        TAG,
                        "unregisterMediaSource(device=$device, source=$source):" +
                            " Remove device, this is the last source",
                    )
                    mediaAudioDevices.remove(device)
                    if (getActiveDevice() == mediaAudioDevice) {
                        setActiveDevice(null)
                    }

                    if (size != mediaAudioDevices.size) {
                        Log.i(
                            TAG,
                            "unregisterMediaSource(device=$device, source=$source): Removed device",
                        )
                        onRootContentsChanged()
                    }
                }
            }
        }
    }

    /**
     * Called by external audio protocols to unregister a protocol interface for a device. If the
     * audio source belongs to the active device, the stream is released for them. The device may
     * remain active if there's still a MediaSource tied to the device.
     *
     * If no protocols remain for a device, the device object is removed.
     *
     * @param device The unique identifier for the hardware device.
     * @param source The protocol that is disconnecting.
     */
    fun unregisterAudioSource(source: AudioSource) {
        postDeviceOperationBlocking {
            val device = source.device

            Log.i(TAG, "unregisterAudioSource(device=$device, source=$source)")

            val size = mediaAudioDevices.size
            val mediaAudioDevice = mediaAudioDevices.get(device)
            if (mediaAudioDevice != null) {
                mediaAudioDevice.removeAudioSource(source)

                if (
                    mediaAudioDevice.getMediaProtocols().isEmpty() &&
                        mediaAudioDevice.getAudioProtocols().isEmpty()
                ) {
                    Log.i(
                        TAG,
                        "unregisterAudioSource(device=$device, source=$source):" +
                            " Remove device, this is the last source",
                    )
                    mediaAudioDevices.remove(device)
                    if (getActiveDevice() == mediaAudioDevice) {
                        setActiveDevice(null)
                    }

                    if (size != mediaAudioDevices.size) {
                        Log.i(
                            TAG,
                            "unregisterAudioSource(device=$device, source=$source): Removed device",
                        )
                        onRootContentsChanged()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Audio Focus Management Abstraction
    // ---------------------------------------------------------------------------------------------

    /* Get the current Audio Focus state for Bluetooth Media/Audio */
    fun getAudioFocusState(): Int {
        return audioFocusState.get()
    }

    /* Set the current Audio Focus state for Bluetooth Media/Audio */
    private fun setAudioFocusState(focusState: Int) {
        audioFocusState.set(focusState)
    }

    /* Our internal callback handler, used with AudioManager focus requests */
    // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
    // @TestApi, which cannot be access from the tests, as our unit tests are module tests. Mark
    // this function as public so the tests can use it
    @VisibleForTesting
    fun onAudioFocusStateChanged(focus: Int) {
        postDeviceOperation {
            Log.i(
                TAG,
                "onAudioFocusStateChanged(" +
                    "from=${MediaAudioUtils.audioFocusToString(getAudioFocusState())}, " +
                    "to=${MediaAudioUtils.audioFocusToString(focus)}" +
                    ")",
            )
            val oldState = getAudioFocusState()
            setAudioFocusState(focus)
            when (focus) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Signal to Media/Audio Framework that we should get media key focus
                    requestMediaKeyFocus()

                    // Notify source of gain by telling them to start
                    getActiveDevice()?.startStream()

                    // Reestablish stream state if recovering from a transient loss
                    if (shouldSendPlayOnFocusRecovery) {
                        Log.d(
                            TAG,
                            "onAudioFocusStateChanged(): " +
                                "Regained focus, establishing play status",
                        )
                        play()
                    }
                    shouldSendPlayOnFocusRecovery = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Tell active source to make the volume duck.
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    getActiveDevice()?.suspendStream()

                    // Send a courtesy pause. Since the loss is transient, save the playing
                    // state so we can attempt to re-establish playback later when we gain focus
                    // back
                    if (getPlaybackStatus()?.state == MediaSource.PlaybackState.PLAYING) {
                        Log.d(
                            TAG,
                            "onAudioFocusStateChanged(): " +
                                "Transient loss, temporarily pause with intent to recover",
                        )
                        pause()
                        shouldSendPlayOnFocusRecovery = true
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    getActiveDevice()?.suspendStream()

                    // Relinquish focus
                    abandonAudioFocus()

                    // Pause stream if playing, if we were going to recover playback, now we're
                    // not, as we don't have focus
                    if (getPlaybackStatus()?.state == MediaSource.PlaybackState.PLAYING) {
                        Log.d(TAG, "onAudioFocusStateChanged(): Lost focus, send courtesy pause")
                        pause()
                    }
                    shouldSendPlayOnFocusRecovery = false
                }
                else -> {
                    Log.w(
                        TAG,
                        "onAudioFocusStateChanged(): Unhandled state=" +
                            "${MediaAudioUtils.audioFocusToString(focus)}",
                    )
                }
            }

            if (focus != oldState) {
                BluetoothMediaBrowserService.onAudioFocusStateChanged(focus)
            }
        }
    }

    /* Request permission from AudioManager to become the application with focus, so we can begin
     * playback of audio.
     */
    private fun requestAudioFocus(): Int {
        Log.d(TAG, "requestAudioFocus()")

        if (getAudioFocusState() == AudioManager.AUDIOFOCUS_GAIN) {
            Log.d(TAG, "requestAudioFocus(): We already have audio focus")
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        val streamAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build()

        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(streamAttributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()

        val status = audioManager.requestAudioFocus(focusRequest)

        // Immediately handle the result if it was granted
        if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN)
        } else {
            Log.w(TAG, "requestAudioFocus(): audio focus not granted yet, status=$status")
            // Because we don't support accepting delayed focus gains, a non-granted return status
            // will ALWAYS be "FAILED" and we can always pause if we were playing. A pause here
            // indicates a failed automatic focus request
            if (getPlaybackStatus()?.state == MediaSource.PlaybackState.PLAYING) {
                Log.w(TAG, "requestAudioFocus(): no focus, pause stream")
                pause()
            }
        }
        return status
    }

    /* Give back audio focus, allowing other applications to play audio. */
    private fun abandonAudioFocus() {
        Log.d(TAG, "abandonAudioFocus()")
        releaseMediaKeyFocus()
        audioManager.abandonAudioFocus(audioFocusListener)
        setAudioFocusState(AudioManager.AUDIOFOCUS_NONE)
    }

    /**
     * Plays a silent audio sample so that MediaSessionService will be aware of the fact that
     * Bluetooth is playing audio. This is used as part of their internal logic to decide which
     * process's MediaSession is allowed to have media key events routed to it.
     *
     * Creates a new MediaPlayer if one does not already exist. Repeat calls to this function are
     * safe and will result in the silent audio sample again.
     */
    private fun requestMediaKeyFocus() {
        Log.d(TAG, "requestMediaKeyFocus(): with mediaPlayer=$mediaPlayer")
        if (mediaPlayer == null) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val newPlayer =
                MediaPlayer.create(
                    context,
                    R.raw.silent,
                    attrs,
                    audioManager.generateAudioSessionId(),
                )

            if (newPlayer == null) {
                Log.e(TAG, "requestMediaKeyFocus(): Failed to initialize media player")
                return
            }

            newPlayer.setLooping(false)
            newPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "Silent media player error: $what, $extra")
                releaseMediaKeyFocus()
                true
            }

            mediaPlayer = newPlayer
            Log.v(TAG, "requestMediaKeyFocus(): Created mediaPlayer=$mediaPlayer")
        }

        mediaPlayer?.start()
        Log.v(TAG, "requestMediaKeyFocus(): Started mediaPlayer=$mediaPlayer")
    }

    /**
     * Destroys the silent audio sample MediaPlayer, signaling to MediaSessionService that we're no
     * longer playing audio. This will allow a different application to receive media key events.
     */
    private fun releaseMediaKeyFocus() {
        Log.d(TAG, "releaseMediaKeyFocus(): with mediaPlayer=$mediaPlayer")
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Determine if we can automatically request focus given the underlying device type and config
     *
     * Android media applications are expected to maintain audio focus when they wish to play back
     * audio. This allows them to coexist with other media apps politely. With native apps, this is
     * usually pretty straightforward, where an app will request focus when someone presses play.
     * This is usually enough to establish user intent for your app to play, so you don't step on
     * other applications by being too aggressive. However, it's not as simple for Bluetooth.
     *
     * Bluetooth playback can start remotely, possibly as the result of something totally out of the
     * control of our local user here. Due to the nature of the Bluetooth media and audio profiles
     * being separate, this playback can yield both a stream state of playing (audio profiles) and,
     * hopefully, a media playback state of playing (media profiles). Simply receiving one of these
     * states is not usually enough to know that the local user _intends_ for Bluetooth to start
     * playing over another app. It can be incredibly disruptive one some form factors.
     *
     * This disruptiveness usually looks like a remote device sending notifications down A2DP (i.e.
     * notification noises, keyboard clicks, etc.) and/or sending playback states for those too.
     *
     * Many form factors require a local user action as an indicator of user intent to play over
     * Bluetooth, including the play, play from media ID, next, and previous commands. We also use
     * the "prepare" event to indicate user intent to play, typically used in some implementations
     * when a user "selects Bluetooth as the source." (think opening the Bluetooth app or selecting
     * Bluetooth input with a hardware button).
     *
     * Despite this, some device manufacturers are willing to trade the disruptiveness caused by
     * some devices for the generally improved UX of automatic focus requesting with all devices.
     *
     * This function returns true in the case that focus can currently be automatically requested.
     */
    private fun canAutomaticallyRequestFocus(): Boolean {
        if (context.isIotDevice() || context.isTv() || context.isXrDevice()) {
            Log.d(TAG, "canAutomaticallyRequestFocus(): true, due to supported device type")
            return true
        }

        Log.d(TAG, "canAutomaticallyRequestFocus(): false")
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Media/Audio Source Management for Metadata, Control, Browsing, Focus and Active Device
    // ---------------------------------------------------------------------------------------------

    fun getMetadata(): MediaSource.Metadata? {
        Log.v(TAG, "getMetadata()")
        val device = getActiveDevice()
        if (device == null) {
            Log.w(TAG, "getMetadata(): no active media source")
            return null
        }
        return device.getMetadata()
    }

    fun getPlaybackStatus(): MediaSource.PlaybackStatus? {
        Log.v(TAG, "getPlaybackStatus()")
        val device = getActiveDevice()
        if (device == null) {
            Log.w(TAG, "getPlaybackStatus(): no active media source")
            return null
        }
        return device.getPlaybackStatus()
    }

    fun getNowPlayingList(): List<MediaSource.Metadata> {
        Log.v(TAG, "getNowPlayingList()")
        val device = getActiveDevice()
        if (device == null) {
            Log.w(TAG, "getNowPlayingList(): no active media source")
            return emptyList()
        }
        return device.getNowPlayingList() ?: emptyList()
    }

    fun getStreamState(): AudioSource.StreamState? {
        Log.v(TAG, "getStreamState()")
        val device = getActiveDevice()
        if (device == null) {
            Log.w(TAG, "getStreamState(): no active media source")
            return null
        }
        return device.getStreamState()
    }

    // ---------------------------------------------------------------------------------------------
    // Playback Controls
    // ---------------------------------------------------------------------------------------------

    fun prepare() {
        postDeviceOperation {
            Log.i(TAG, "prepare()")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "prepare(): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "prepare(): no active media source")
            } else {
                device.prepare()
            }

            Log.v(TAG, "prepare(): have MediaPlayer = " + mediaPlayer)
        }
    }

    fun play() {
        postDeviceOperation {
            Log.i(TAG, "play()")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "play(): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "play(): no active media source")
            } else {
                device.play()
            }
        }
    }

    fun pause() {
        postDeviceOperation {
            Log.i(TAG, "pause()")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "pause(): no active media source")
            } else {
                if (getAudioFocusState() == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Log.d(
                        TAG,
                        "Received a pause while in a transient loss. Do not recover anymore.",
                    )
                    shouldSendPlayOnFocusRecovery = false
                }
                device.pause()
            }
        }
    }

    fun skipToNext() {
        postDeviceOperation {
            Log.i(TAG, "skipToNext()")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "skipToNext(): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "skipToNext(): no active media source")
            } else {
                device.skipToNext()
            }
        }
    }

    fun skipToPrevious() {
        postDeviceOperation {
            Log.i(TAG, "skipToPrevious()")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "skipToPrevious(): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "skipToPrevious(): no active media source")
            } else {
                device.skipToPrevious()
            }
        }
    }

    fun skipToQueueItem(id: Long) {
        postDeviceOperation {
            Log.i(TAG, "skipToQueueItem(id=$id)")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "skipToQueueItem(id=$id): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "skipToQueueItem(id=$id): no active media source")
            } else {
                device.skipToQueueItem(id)
            }
        }
    }

    fun stop() {
        postDeviceOperation {
            Log.i(TAG, "stop()")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "stop(): no active media source")
            } else {
                if (getAudioFocusState() == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Log.d(TAG, "Received a stop while in a transient loss. Do not recover anymore.")
                    shouldSendPlayOnFocusRecovery = false
                }
                device.stop()
            }
        }
    }

    fun rewind() {
        postDeviceOperation {
            Log.i(TAG, "rewind()")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "rewind(): no active media source")
            } else {
                device.rewind()
            }
        }
    }

    fun fastForward() {
        postDeviceOperation {
            Log.i(TAG, "fastForward()")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "fastForward(): no active media source")
            } else {
                device.fastForward()
            }
        }
    }

    fun playFromMediaId(id: String) {
        postDeviceOperation {
            Log.i(TAG, "playFromMediaId(id=$id)")
            val device = getActiveDevice()
            if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "playFromMediaId(id=$id): was not granted audio focus")
            } else if (device == null) {
                Log.w(TAG, "playFromMediaId(): no active media source")
            } else {
                device.playFromMediaId(id)
            }
        }
    }

    fun setRepeatMode(mode: MediaSource.RepeatMode) {
        postDeviceOperation {
            Log.i(TAG, "setRepeatMode(mode=$mode)")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "setRepeatMode(): no active media source")
            } else {
                device.setRepeatMode(mode)
            }
        }
    }

    fun setShuffleMode(mode: MediaSource.ShuffleMode) {
        postDeviceOperation {
            Log.i(TAG, "setShuffleMode(mode=$mode)")
            val device = getActiveDevice()
            if (device == null) {
                Log.w(TAG, "setShuffleMode(): no active media source")
            } else {
                device.setShuffleMode(mode)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------------

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun browse(request: MediaSource.BrowseRequest?): MediaSource.BrowseResult {
        Log.i(TAG, "browse(request=$request)")

        if (request == null) {
            Log.i(TAG, "browse(request=null): Bad request")
            return MediaSource.BrowseResult(null, MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID)
        }

        val parentMediaId = request.mediaId
        val currentMediaAudioDevices = mediaAudioDevices.values.toList()

        if (parentMediaId == BROWSER_ROOT) {
            if (currentMediaAudioDevices.isEmpty()) {
                Log.i(TAG, "browse(request=$request): No devices connected")
                return MediaSource.BrowseResult(
                    null,
                    MediaSource.BrowseStatus.ERROR_NO_DEVICE_CONNECTED,
                )
            }

            val devices = currentMediaAudioDevices.mapNotNull { it.getDeviceRoot() }
            return MediaSource.BrowseResult(devices, MediaSource.BrowseStatus.SUCCESS)
        }

        for (device in currentMediaAudioDevices) {
            val result = device.browse(request)
            if (
                result.status == MediaSource.BrowseStatus.SUCCESS ||
                    result.status == MediaSource.BrowseStatus.DOWNLOAD_PENDING
            ) {
                Log.i(TAG, "browse(request=$request): Found node, device=$device")
                postDeviceOperation {
                    Log.i(TAG, "setActiveDeviceToBrowsedDevice(device=$device)")
                    if (device in mediaAudioDevices.values.toList()) {
                        setActiveDevice(device)
                    } else {
                        Log.w(TAG, "setActiveDeviceToBrowsedDevice(device=$device): device gone")
                    }
                }
                return result
            }
        }

        return MediaSource.BrowseResult(
            emptyList(),
            MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Device Event Handling
    // ---------------------------------------------------------------------------------------------

    private inner class MediaAudioDeviceCallback(private val device: BluetoothDevice) :
        MediaAudioDevice.Callback {

        override fun onMetadataChanged(metadata: MediaSource.Metadata?) {
            postDeviceOperation {
                Log.i(TAG, "[$device] onMetadataChanged(metadata=$metadata)")
                if (getActiveDevice()?.device == device) {
                    BluetoothMediaBrowserService.onTrackChanged(metadata)
                }
            }
        }

        override fun onPlaybackStatusChanged(playbackStatus: MediaSource.PlaybackStatus?) {
            postDeviceOperation {
                val currentStatus = getPlaybackStatus()
                if (
                    playbackStatus?.state != currentStatus?.state ||
                        playbackStatus?.shuffleMode != currentStatus?.shuffleMode ||
                        playbackStatus?.repeatMode != currentStatus?.repeatMode
                ) {
                    Log.i(TAG, "[$device] onPlaybackStatusChanged(playbackStatus=$playbackStatus)")
                } else {
                    // Playback position changes update our state here, which can be very spammy
                    Log.v(TAG, "[$device] onPlaybackStatusChanged(playbackStatus=$playbackStatus)")
                }

                val isPlaying = playbackStatus?.state == MediaSource.PlaybackState.PLAYING
                val isActiveDevice = getActiveDevice()?.device == device
                val currentFocusState = getAudioFocusState()
                val mediaAudioDevice = mediaAudioDevices.get(device)
                if (isPlaying && !isActiveDevice) {
                    Log.i(
                        TAG,
                        "[$device] onPlaybackStatusChanged(playbackStatus=$playbackStatus): " +
                            "playing while not active, pausing (isActiveDevice=$isActiveDevice)" +
                            ", pausing",
                    )
                    mediaAudioDevice?.pause()
                } else if (isPlaying && currentFocusState != AudioManager.AUDIOFOCUS_GAIN) {
                    if (canAutomaticallyRequestFocus()) {
                        Log.d(
                            TAG,
                            "[$device] onPlaybackStatusChanged(playbackStatus=$playbackStatus): " +
                                "Automatically trying to request audio focus",
                        )
                        requestAudioFocus()
                    } else {
                        Log.i(
                            TAG,
                            "[$device] onPlaybackStatusChanged(playbackStatus=$playbackStatus): " +
                                "focus_state=" +
                                "${MediaAudioUtils.audioFocusToString(currentFocusState)}" +
                                ", pausing",
                        )
                        mediaAudioDevice?.pause()
                    }
                }

                if (getActiveDevice()?.device == device) {
                    BluetoothMediaBrowserService.onPlaybackStateChanged(playbackStatus)
                }
            }
        }

        override fun onNowPlayingListChanged(queue: List<MediaSource.Metadata>?) {
            postDeviceOperation {
                Log.i(TAG, "[$device] onNowPlayingListChanged(queue=$queue)")
                if (getActiveDevice()?.device == device) {
                    BluetoothMediaBrowserService.onNowPlayingQueueChanged(queue)
                }
            }
        }

        override fun onBrowseNodeChanged(id: String) {
            postDeviceOperation {
                Log.i(TAG, "[$device] onBrowseNodeChanged(id=$id)")
                BluetoothMediaBrowserService.onBrowseNodeChanged(id)
            }
        }

        override fun onAudioStreamStateChanged(state: AudioSource.StreamState?) {
            postDeviceOperation {
                Log.i(TAG, "[$device] onAudioStreamStateChanged(state=$state)")

                val currentFocusState = getAudioFocusState()
                if (
                    state == AudioSource.StreamState.STREAMING &&
                        currentFocusState != AudioManager.AUDIOFOCUS_GAIN
                ) {
                    if (canAutomaticallyRequestFocus()) {
                        Log.d(
                            TAG,
                            "[$device] onAudioStreamStateChanged(state=$state): " +
                                "focus_state=" +
                                "${MediaAudioUtils.audioFocusToString(currentFocusState)}" +
                                ", but can automatically request focus",
                        )
                        requestAudioFocus()
                    } else {
                        Log.i(
                            TAG,
                            "[$device] onAudioStreamStateChanged(state=$state): " +
                                "focus_state" +
                                "=${MediaAudioUtils.audioFocusToString(currentFocusState)}" +
                                ", pausing",
                        )
                        val mediaAudioDevice = mediaAudioDevices.get(device)
                        mediaAudioDevice?.pause()
                    }
                }
            }
        }
    }

    private fun onRootContentsChanged() {
        postDeviceOperation {
            Log.i(TAG, "onRootContentsChanged(id=$BROWSER_ROOT)")
            BluetoothMediaBrowserService.onBrowseNodeChanged(BROWSER_ROOT)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Event Serialization
    // ---------------------------------------------------------------------------------------------

    /**
     * Posts a Runnable to the executor. This is the safe and preferred way to perform any device
     * based state modification or action, ensuring source events and requests to add and remove are
     * all serialized, handled in the order they arrive.
     *
     * @param block The code block to execute while holding the lock on the handler thread.
     */
    private fun postDeviceOperation(block: () -> Unit) = executor.submit(block)

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
    private fun <T> postDeviceOperationBlocking(block: () -> T): T = executor.submit(block).get()

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    fun dump(sb: StringBuilder) {
        sb.appendLine("Service: MediaAudioServer")
        sb.appendLine("    Media Enabled: ${isMediaEnabled()}")
        sb.appendLine("    Audio Enabled: ${isAudioEnabled()}")
        sb.appendLine("    Active Device: ${getActiveDevice()?.device}")
        sb.appendLine(
            "    Audio Focus State: " +
                "${MediaAudioUtils.audioFocusToString(getAudioFocusState())}"
        )
        sb.appendLine()

        if (isMediaEnabled()) {
            sb.appendLine("    Media State:")
            sb.appendLine("        metadata=${getMetadata()}")
            sb.appendLine("        playback_status=${getPlaybackStatus()}")
            sb.appendLine("        queue=${getNowPlayingList()}")
        }

        if (isAudioEnabled()) {
            sb.appendLine("\n    Audio State:")
            sb.appendLine("        stream_state=${getStreamState()}")
        }

        sb.appendLine()
        sb.appendLine("    MediaAudioDevices(${mediaAudioDevices.size}):")
        for (device in mediaAudioDevices.values) {
            sb.append("        ")
            if (device == getActiveDevice()) {
                sb.append("(Active) ")
            }
            sb.appendLine(device.dump())
        }

        sb.appendLine()
        sb.append(BluetoothMediaBrowserService.dump())
    }
}
