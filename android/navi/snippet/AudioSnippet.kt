/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioManager.OnCommunicationDeviceChangedListener
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.rpc.RpcOptional
import com.google.android.mobly.snippet.rpc.RunOnUiThread
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AudioSnippet : Snippet {
    private data class AudioCallbacks(
        val audioDeviceCallback: AudioDeviceCallback,
        val communicationDeviceChangedListener: OnCommunicationDeviceChangedListener,
        val broadcastReceiver: BroadcastReceiver,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    internal var player = ExoPlayer.Builder(context).build()
    private val callbacks = mutableMapOf<String, AudioCallbacks>()
    private val playerListeners = mutableMapOf<String, Player.Listener>()
    private val mainHandler = Handler(context.mainLooper)
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(dispatcher + Job())
    internal val recorders = mutableMapOf<String, Pair<AudioRecord, Deferred<List<Byte>>>>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        context.mainExecutor.execute {
            MediaSession.Builder(context, player).build()
            // Add a default media.
            val fileUri =
                Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .path(R.raw.sine1000hz.toString())
                    .build()
            player.setMediaItem(MediaItem.fromUri(fileUri))
            player.prepare()
        }
    }

    /** Registers an Audio snippet callback with [callbackId]. */
    @AsyncRpc(description = "Registers an Audio snippet callback")
    fun audioRegisterCallback(callbackId: String) {
        val audioDeviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    for (addedDevice in addedDevices) {
                        postSnippetEvent(callbackId, SnippetConstants.AUDIO_DEVICE_ADDED) {
                            putString(SnippetConstants.FIELD_DEVICE, addedDevice.address)
                            putInt(SnippetConstants.FIELD_TRANSPORT, addedDevice.type)
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    for (removedDevice in removedDevices) {
                        postSnippetEvent(callbackId, SnippetConstants.AUDIO_DEVICE_REMOVED) {
                            putString(SnippetConstants.FIELD_DEVICE, removedDevice.address)
                            putInt(SnippetConstants.FIELD_TRANSPORT, removedDevice.type)
                        }
                    }
                }
            }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)

        val onCommunicationDeviceChangedListener =
            object : OnCommunicationDeviceChangedListener {
                override fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
                    if (device == null) {
                        return
                    }
                    postSnippetEvent(
                        callbackId,
                        SnippetConstants.AUDIO_COMMUNICATION_DEVICE_CHANGED,
                    ) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_TRANSPORT, device.type)
                    }
                }
            }
        audioManager.addOnCommunicationDeviceChangedListener(
            context.mainExecutor,
            onCommunicationDeviceChangedListener,
        )

        val broadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        AudioManager.ACTION_VOLUME_CHANGED -> {
                            postSnippetEvent(callbackId, SnippetConstants.VOLUME_CHANGED) {
                                putInt(
                                    SnippetConstants.FIELD_TYPE,
                                    intent.getIntExtra(
                                        AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                                        AudioManager.ERROR,
                                    ),
                                )
                                putInt(
                                    SnippetConstants.FIELD_VALUE,
                                    intent.getIntExtra(
                                        AudioManager.EXTRA_VOLUME_STREAM_VALUE,
                                        AudioManager.ERROR,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(AudioManager.ACTION_VOLUME_CHANGED),
        )

        callbacks[callbackId] =
            AudioCallbacks(
                audioDeviceCallback,
                onCommunicationDeviceChangedListener,
                broadcastReceiver,
            )
    }

    /** Unregisters an Audio snippet callback with [callbackId]. */
    @Rpc(description = "Registers an Audio snippet callback")
    fun audioUnregisterCallback(callbackId: String) {
        callbacks.remove(callbackId)?.also {
            (audioDeviceCallback, onCommunicationDeviceChangedListener, broadcastReceiver) ->
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioManager.removeOnCommunicationDeviceChangedListener(
                onCommunicationDeviceChangedListener
            )
            context.unregisterReceiver(broadcastReceiver)
        }
    }

    /** Registers a player snippet callback with [callbackId]. */
    @AsyncRpc(description = "Registers a player snippet callback")
    fun registerPlayerListener(callbackId: String) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "isPlayingChanged: $isPlaying")
                    postSnippetEvent(callbackId, SnippetConstants.PLAYER_IS_PLAYING_CHANGED) {
                        putBoolean(SnippetConstants.FIELD_STATE, isPlaying)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d(TAG, "onMediaItemTransition: $mediaItem, $reason")
                    postSnippetEvent(callbackId, SnippetConstants.PLAYER_MEDIA_ITEM_TRANSITION) {
                        mediaItem?.localConfiguration?.uri.let {
                            putString(SnippetConstants.URI, it.toString())
                        }
                        putInt(SnippetConstants.FIELD_REASON, reason)
                    }
                }
            }
        player.addListener(listener)
        playerListeners[callbackId] = listener
    }

    /** Unregisters a player snippet callback with [callbackId]. */
    @Rpc(description = "Unregisters a player snippet callback")
    @RunOnUiThread
    fun unregisterPlayerListener(callbackId: String) {
        playerListeners.remove(callbackId)?.let { player.removeListener(it) }
    }

    /** Set offload of audio playback. */
    @Rpc(description = "Set offload of audio playback")
    @RunOnUiThread
    fun setAudioPlaybackOffload(enabled: Boolean) {
        val audioOffloadPreferences =
            if (enabled) {
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .build()
            } else {
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            }
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffloadPreferences)
                .build()
    }

    /** Set handle audio becoming noisy. */
    @Rpc(description = "Set handle audio becoming noisy")
    @RunOnUiThread
    fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        player.setHandleAudioBecomingNoisy(enabled)
    }

    /** Sets audio attribute of player to [attributes] and [handleAudioFocus]. */
    @Rpc(description = "Set Audio Attribute")
    @RunOnUiThread
    fun setAudioAttributes(attributes: AudioAttributes?, handleAudioFocus: Boolean) {
        player.setAudioAttributes(attributes ?: player.audioAttributes, handleAudioFocus)
    }

    /** Plays 1000Hz sine wave. */
    @Rpc(description = "Play 1000Hz sine wave")
    @RunOnUiThread
    fun audioPlaySine() {
        val fileUri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .path(R.raw.sine1000hz.toString())
                .build()
        player.setMediaItem(MediaItem.fromUri(fileUri))
        player.prepare()
        player.play()
    }

    /** Plays audio file with [fileUri] . */
    @Rpc(description = "Play audio from a given file path")
    @RunOnUiThread
    fun audioPlayFile(fileUri: String) {
        player.setMediaItem(MediaItem.fromUri(fileUri))
        player.prepare()
        player.play()
    }

    /** Sets player repeat mode to [repeatMode]. */
    @Rpc(description = "Set repeat mode")
    @RunOnUiThread
    fun audioSetRepeat(@Player.RepeatMode repeatMode: Int) {
        player.repeatMode = repeatMode
    }

    /** Resumes playing audio. */
    @Rpc(description = "Resume playing audio")
    @RunOnUiThread
    fun audioResume() {
        player.play()
    }

    /** Pauses playing audio. */
    @Rpc(description = "Pause playing audio")
    @RunOnUiThread
    fun audioPause() {
        player.pause()
    }

    /** Stops playing audio. */
    @Rpc(description = "stop playing audio")
    @RunOnUiThread
    fun audioStop() {
        player.stop()
    }

    /** Add a media item to the player. */
    @Rpc(description = "Add a media item")
    @RunOnUiThread
    fun addMediaItem(fileUri: String) {
        player.addMediaItem(MediaItem.fromUri(fileUri))
    }

    /** Starts a recorder streaming to [outputPath]. */
    @Rpc(description = "Start recording")
    fun startRecording(outputPath: String) {
        if (outputPath in recorders) {
            throw IllegalArgumentException("$outputPath is already recording")
        }
        val bufferSize =
            AudioRecord.getMinBufferSize(
                48000,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) * 2
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                48000,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        val deferred =
            coroutineScope.async {
                val outputBuffer = mutableListOf<Byte>()
                val buffer = ByteArray(bufferSize)
                while (recorder.read(buffer, 0, buffer.size) > 0) {
                    outputBuffer.addAll(buffer.asList())
                }
                Log.d(TAG, "Recording ${outputPath} stopped")
                outputBuffer
            }
        recorder.startRecording()
        recorders[outputPath] = Pair(recorder, deferred)
    }

    /** Stops a recorder streaming to [outputPath]. */
    @Rpc(description = "Stop recording")
    @RunOnUiThread
    fun stopRecording(outputPath: String) {
        recorders.remove(outputPath)?.let { (recorder, deferred) ->
            recorder.stop()
            recorder.release()
            val outputBuffer = runBlocking { withTimeout(1.seconds) { deferred.await() } }
            FileOutputStream(outputPath).use {
                // Write the wave header.
                it.write(
                    ByteBuffer.allocate(44)
                        .apply {
                            order(ByteOrder.LITTLE_ENDIAN)
                            put(WAVE_HEADER_RIFF)
                            putInt(outputBuffer.size + 44) // File size
                            put(WAVE_HEADER_WAVE)
                            put(WAVE_HEADER_FMT)
                            putInt(16) // Size of previous headers
                            putShort(WAVE_HEADER_TYPE_PCM) // Format of data
                            putShort(2) // Stereo
                            putInt(48000) // Sample rate
                            putInt(48000 * 2 * 16 / 8) // Bytes per second
                            putShort((2 * 16 / 8).toShort()) // Frame size
                            putShort(16) // Bits per sample
                            put(WAVE_HEADER_DATA) // data
                            putInt(outputBuffer.size) // Data size
                        }
                        .array()
                )
                it.write(outputBuffer.toByteArray())
            }
        } ?: throw IllegalArgumentException("$outputPath is not recording")
    }

    /**
     * Enables SCO route to Bluetooth SCO device with [address]. If address is not passed, sets the
     * active device to the first SCO device.
     */
    @Rpc(description = "Enable SCO route")
    @RunOnUiThread
    fun audioSetRouteSco(@RpcOptional address: String?) {
        audioManager.availableCommunicationDevices
            .first {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    (it.address == address || address == null)
            }
            .let { audioDevice ->
                audioManager.setCommunicationDevice(audioDevice)
                player.setPreferredAudioDevice(audioDevice)
            }
    }

    /** Sets audio route to default. */
    @Rpc(description = "Set audio route to default")
    @RunOnUiThread
    fun audioSetRouteDefault() {
        audioManager.clearCommunicationDevice()
        player.setPreferredAudioDevice(null)
    }

    /** Sets volume of [streamType] to [volume]. */
    @Rpc(description = "Set volume")
    fun setVolume(streamType: Int, volume: Int) {
        audioManager.setStreamVolume(streamType, volume, AudioManager.FLAG_SHOW_UI)
    }

    /** Gets the current volume of [streamType]. */
    @Rpc(description = "Get current volume")
    fun getVolume(streamType: Int): Int = audioManager.getStreamVolume(streamType)

    /** Gets the max volume of [streamType]. */
    @Rpc(description = "Get the max volume")
    fun getMaxVolume(streamType: Int): Int = audioManager.getStreamMaxVolume(streamType)

    /** Gets the min volume of [streamType]. */
    @Rpc(description = "Get the min volume")
    fun getMinVolume(streamType: Int): Int = audioManager.getStreamMinVolume(streamType)

    private companion object {
        const val TAG = "AudioSnippet"
        val WAVE_HEADER_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val WAVE_HEADER_WAVE = byteArrayOf(0x57, 0x41, 0x56, 0x45)
        val WAVE_HEADER_FMT = byteArrayOf(0x66, 0x6d, 0x74, 0x20)
        val WAVE_HEADER_DATA = byteArrayOf(0x64, 0x61, 0x74, 0x61)
        const val WAVE_HEADER_TYPE_PCM: Short = 1
    }
}
