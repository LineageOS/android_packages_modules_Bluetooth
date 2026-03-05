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

import android.media.AudioManager
import android.media.AudioManager.OnCommunicationDeviceChangedListener
import android.media.MediaRouter

/** The interface for media controller. */
interface IMediaController {
    fun addCommunicationDeviceChangedListener(listener: OnCommunicationDeviceChangedListener)

    fun removeCommunicationDeviceChangedListener(listener: OnCommunicationDeviceChangedListener)

    fun addMediaRouterCallback(callback: MediaRouter.Callback)

    fun removeMediaRouterCallback(callback: MediaRouter.Callback)

    fun requestAudioFocus()

    fun releaseAudioFocus()

    fun registerVolumeChangedReceiver()

    fun initMediaSession(id: String)

    fun resetAudioVolume(audioSrc: Int = AudioManager.STREAM_MUSIC)

    fun playMusic(src: MediaSource = MediaSource.SONG)

    fun playMusicFromList()

    fun resumeMusic()

    fun pauseMusic()

    fun playRingtone()

    fun stopRingtone()

    fun resetMediaSession(id: String)

    fun reset()

    fun isPlaying(): Boolean

    fun getVolume(source: Int): Int

    fun addMediaStateListener(listener: MediaStateListener)

    fun removeMediaStateListener(listener: MediaStateListener)

    fun addVolumeChangedListener(listener: VolumeChangedListener)

    fun removeVolumeChangedListener(listener: VolumeChangedListener)

    fun addAudioFocusChangeListener(listener: AudioFocusChangeListener)

    fun removeAudioFocusChangeListener(listener: AudioFocusChangeListener)

    fun addTrackChangeListener(listener: TrackChangeListener)

    fun removeTrackChangeListener(listener: TrackChangeListener)

    enum class MediaSource {
        SONG,
        SPEECH,
    }
}

/** The listener for media play/ pause state changes. */
interface MediaStateListener {
    /**
     * Called when the music playing state changes.
     *
     * @param isPlaying The new playing state of the music.
     */
    fun onMusicPlayingStateChanged(isPlaying: Boolean)
}

/** The listener for media volume changes. */
interface VolumeChangedListener {
    /** Called when the media volume is changed. */
    fun onVolumeChanged()
}

/** The listener for audio focus changes. */
interface AudioFocusChangeListener {
    /** Called when the audio focus is changed. */
    fun onAudioFocusChange(newStatus: String)
}

/** The listener for track changes. */
interface TrackChangeListener {
    /** Called when the track is changed. */
    fun onTrackChanged(oldIndex: Int, newIndex: Int)
}
