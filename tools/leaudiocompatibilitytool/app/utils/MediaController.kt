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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.annotation.SuppressLint
import android.bluetooth.tools.leaudiocompatibilitytool.R
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController.MediaSource
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnCommunicationDeviceChangedListener
import android.media.MediaRouter
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.common.annotations.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A controller for media related events. */
@Singleton
class MediaController @Inject constructor(@ApplicationContext private val context: Context) :
    IMediaController {
    @VisibleForTesting var mediaPlayer = ExoPlayer.Builder(context).build()
    @VisibleForTesting val mediaSessions = mutableMapOf<String, MediaSession>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaStateListeners = mutableListOf<MediaStateListener>()
    private val volumeChangedListeners = mutableListOf<VolumeChangedListener>()
    private val audioFocusChangeListeners = mutableListOf<AudioFocusChangeListener>()
    private val trackChangeListeners = mutableListOf<TrackChangeListener>()
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

    /**
     * Initializes the media controller by registering receivers for play state changed events and
     * track change events. We also specify the media player's media source and repeat mode.
     */
    init {
        Log.d(TAG, "init MediaController")
        registerVolumeChangedReceiver()
        mediaPlayer.setRepeatMode(Player.REPEAT_MODE_ONE)
        mediaPlayer.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "onIsPlayingChanged: $isPlaying")
                    for (listener in mediaStateListeners) {
                        listener.onMusicPlayingStateChanged(isPlaying)
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // Check if the reason was a seek
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        val oldIndex = oldPosition.mediaItemIndex
                        val newIndex = newPosition.mediaItemIndex

                        for (listener in trackChangeListeners) {
                            listener.onTrackChanged(oldIndex, newIndex)
                        }
                    }
                }
            }
        )
    }

    /** Registers a listener for communication device changed events. */
    override fun addCommunicationDeviceChangedListener(
        listener: OnCommunicationDeviceChangedListener
    ) {
        audioManager.addOnCommunicationDeviceChangedListener(handler::post, listener)
    }

    /** Unregisters a listener for communication device changed events. */
    override fun removeCommunicationDeviceChangedListener(
        listener: OnCommunicationDeviceChangedListener
    ) {
        audioManager.removeOnCommunicationDeviceChangedListener(listener)
    }

    /** Registers a [MediaRouter.Callback] to listen for media route changes. */
    override fun addMediaRouterCallback(callback: MediaRouter.Callback) {
        mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        Log.d(TAG, "Registered MediaRouter.Callback")
    }

    /** Unregisters a [MediaRouter.Callback]. */
    override fun removeMediaRouterCallback(callback: MediaRouter.Callback) {
        mediaRouter.removeCallback(callback)
        Log.d(TAG, "Removed MediaRouter.Callback")
    }

    /** The listener for audio focus changes. Used in the media call switch test. */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "onAudioFocusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AUDIOFOCUS_GAIN")
                for (listener in audioFocusChangeListeners) {
                    listener.onAudioFocusChange("AUDIOFOCUS_GAIN")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS")
                for (listener in audioFocusChangeListeners) {
                    listener.onAudioFocusChange("AUDIOFOCUS_LOSS")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT")
                for (listener in audioFocusChangeListeners) {
                    listener.onAudioFocusChange("AUDIOFOCUS_LOSS_TRANSIENT")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
                for (listener in audioFocusChangeListeners) {
                    listener.onAudioFocusChange("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
                }
            }
        }
    }

    /** Requests audio focus for media. */
    override fun requestAudioFocus() {
        audioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .build()

        audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
    }

    /** Releases audio focus for media. */
    override fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /**
     * Registers a receiver for volume changed events.
     *
     * This method will be called when the media controller is initialized.
     */
    @SuppressLint("UnprotectedReceiver")
    override fun registerVolumeChangedReceiver() {
        val volumeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    when (action) {
                        AudioManager.ACTION_VOLUME_CHANGED -> {
                            for (listener in volumeChangedListeners) {
                                listener.onVolumeChanged()
                            }
                        }
                    }
                }
            }
        context.registerReceiver(volumeReceiver, IntentFilter(AudioManager.ACTION_VOLUME_CHANGED))
        Log.d(TAG, "registered volumeReceiver")
    }

    /**
     * Initializes the media session by setting the media player's media source and repeat mode.
     *
     * This method will be called when a media related test is initialized.
     */
    override fun initMediaSession(id: String) {
        if (id in mediaSessions) {
            Log.d(TAG, "Media session already exists for id: $id")
            return
        }
        mediaSessions[id] = MediaSession.Builder(context, mediaPlayer).setId(id).build()
        Log.d(TAG, "init mediaSession for id: $id")
    }

    /**
     * Resets the audio volume to 20% of the max volume.
     *
     * @param audioSrc The source of the audio to reset the volume for.
     *
     * This method will be called at the beginning of all media related tests make sure users can
     * hear the music for the test to continue.
     */
    override fun resetAudioVolume(audioSrc: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(audioSrc)
        var percent = 0.2f
        // Increase the volume to 50% for call volume control test.
        if (audioSrc == AudioManager.STREAM_VOICE_CALL) {
            percent = 0.5f
        }

        val adjustedVolume = (maxVolume * percent).toInt()
        audioManager.setStreamVolume(audioSrc, adjustedVolume, 0)
    }

    /** Plays the music. */
    override fun playMusic(src: MediaSource) {
        val mediaUri =
            when (src) {
                MediaSource.SONG -> songUri
                MediaSource.SPEECH -> speechUri
            }
        Log.d(TAG, "playMusic: $mediaUri")
        mediaPlayer.setMediaItem(MediaItem.fromUri(mediaUri))
        mediaPlayer.prepare()
        mediaPlayer.play()
    }

    /** Plays the music from the list. */
    override fun playMusicFromList() {
        mediaPlayer.setMediaItems(listOf(MediaItem.fromUri(songUri), MediaItem.fromUri(speechUri)))
        mediaPlayer.prepare()
        mediaPlayer.play()
    }

    /** Resumes the music. */
    override fun resumeMusic() {
        mediaPlayer.play()
    }

    /** Pauses the music. */
    override fun pauseMusic() {
        mediaPlayer.pause()
    }

    /** Plays the ringtone. */
    override fun playRingtone() {
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val newRingtone = RingtoneManager.getRingtone(context, ringtoneUri)
        newRingtone.play()
        ringtone = newRingtone
    }

    /** Stops the ringtone. */
    override fun stopRingtone() {
        ringtone?.stop()
    }

    /** Releases the media session. */
    override fun resetMediaSession(id: String) {
        pauseMusic()
        mediaSessions.remove(id)?.release()
        Log.d(TAG, "reset mediaSession for id: $id")
    }

    /** Releases the media player and media session. */
    override fun reset() {
        mediaPlayer.release()
        for (mediaSession in mediaSessions.values) {
            mediaSession.release()
        }
        mediaSessions.clear()
        Log.d(TAG, "reset both MediaPlayer and MediaSession")
    }

    /** Returns true if the music is playing. */
    override fun isPlaying(): Boolean {
        return mediaPlayer.isPlaying
    }

    /**
     * Returns the volume of the given source.
     *
     * @param source The source of the volume to get.
     * @return The volume of the given source.
     */
    override fun getVolume(source: Int): Int {
        return audioManager.getStreamVolume(source)
    }

    /** Adds a listener to be called when the music playing state changes. */
    override fun addMediaStateListener(listener: MediaStateListener) {
        this.mediaStateListeners.add(listener)
    }

    /**
     * Removes a listener from the list of listeners to be called when the music playing state
     * changes.
     */
    override fun removeMediaStateListener(listener: MediaStateListener) {
        this.mediaStateListeners.remove(listener)
    }

    /** Adds a listener to be called when the media volume is changed. */
    override fun addVolumeChangedListener(listener: VolumeChangedListener) {
        this.volumeChangedListeners.add(listener)
    }

    /**
     * Removes a listener from the list of listeners to be called when the media volume is changed.
     */
    override fun removeVolumeChangedListener(listener: VolumeChangedListener) {
        this.volumeChangedListeners.remove(listener)
    }

    /** Adds a listener to be called when the audio focus is changed. */
    override fun addAudioFocusChangeListener(listener: AudioFocusChangeListener) {
        this.audioFocusChangeListeners.add(listener)
    }

    /** Removes a listener to be called when the audio focus is changed. */
    override fun removeAudioFocusChangeListener(listener: AudioFocusChangeListener) {
        this.audioFocusChangeListeners.remove(listener)
    }

    /** Adds a listener to be called when the track is changed. */
    override fun addTrackChangeListener(listener: TrackChangeListener) {
        this.trackChangeListeners.add(listener)
    }

    /** Removes a listener to be called when the track is changed. */
    override fun removeTrackChangeListener(listener: TrackChangeListener) {
        this.trackChangeListeners.remove(listener)
    }

    companion object {
        const val TAG = "Media Testing"
        val songUri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .path(R.raw.song.toString())
                .build()
        val speechUri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .path(R.raw.story.toString())
                .build()
    }
}
