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
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "BtAudioSource"

/** Represents a single media-providing source/protocol and its capabilities. */
open class AudioSource(val device: BluetoothDevice, val protocol: Protocol) {

    // ---------------------------------------------------------------------------------------------
    // Source Facade
    // ---------------------------------------------------------------------------------------------

    /* The current audio stream state of this audio source. The states are defined below */
    private var streamState = AtomicReference<StreamState>(StreamState.UNKNOWN)

    // ---------------------------------------------------------------------------------------------
    // Outside Objects for Event Callbacks
    // ---------------------------------------------------------------------------------------------

    /* An option callback to send events back to the AudioSource creator/owner */
    private var callback = AtomicReference<Callback?>(null)

    // ---------------------------------------------------------------------------------------------
    // Data Classes
    // ---------------------------------------------------------------------------------------------

    /* The available protocols for an AudioSource */
    enum class Protocol {
        A2DP_SINK,
        BAP_UNICAST_SERVER,
    }

    /*
     * The possible stream states a Bluetooth audio stream can be in
     *
     * UNKNOWN: No value known or set
     * IDLE: No stream is established and/or no codec is configured
     * CONFIGURED: An endpoint has been selected and the codec/sample rate/channel is set
     * OPEN: Devices are ready to exchange audio data, but have not actively exchanged it yet
     * STREAMING: Devices are active exchanging audio data
     * RELEASING: Stream is being released and resources are being deallocated. Can be intentional
     *            or because something terrible and irrecoverable happened
     */
    enum class StreamState {
        UNKNOWN,
        IDLE,
        CONFIGURED,
        OPEN,
        STREAMING,
        RELEASING,
    }

    /** An interface for MediaAudioServer to get events from this AudioSource as a callback */
    interface Callback {
        fun onStreamStateChanged(state: StreamState)
    }

    // ---------------------------------------------------------------------------------------------
    // Object Public Interfaces
    // ---------------------------------------------------------------------------------------------

    /** Register a callback object to receive events from this AudioSource */
    fun registerCallback(callback: Callback) {
        this.callback.set(callback)
    }

    /** Unregister a callback object to stop receiving events from this AudioSource */
    fun unregisterCallback() {
        this.callback.set(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Stream State Management
    // ---------------------------------------------------------------------------------------------

    fun getStreamState(): StreamState {
        return streamState.get()
    }

    fun setStreamState(state: StreamState) {
        logv("setStreamState(state=$state)")
        streamState.set(state)
        callback.get()?.onStreamStateChanged(streamState.get())
    }

    // ---------------------------------------------------------------------------------------------
    // Stream Commands
    // ---------------------------------------------------------------------------------------------

    /**
     * Signal the user's intent to stream from this specific audio source.
     *
     * Upon receiving this event, the audio source should do whatever it needs to establish
     * readiness for playback, but should not immediately start playback. Because a source should
     * exist and be registered with the server until after a connection has been established, this
     * setup will primarily include ensuring this source is able to play alongside or instead of any
     * other connected sources for your protocol.
     */
    open fun prepare() {}

    /**
     * Starts or resumes audio streaming for this device. Requires the stream to be in a prepared or
     * suspended state.
     */
    open fun start() {}

    /**
     * Suspends active audio streaming for this device. The device remains prepared to quickly
     * resume streaming. If you cannot suspend the stream, then you should at least mute it.
     */
    open fun suspend() {}

    /**
     * Releases all resources associated with this device's audio stream, taking it back to an
     * unprepared state. This may allow other connected devices for the given protocol to prepare
     * and stream.
     */
    open fun release() {}

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    fun logv(msg: String) {
        Log.v(TAG, "[$device, $protocol] $msg")
    }

    override fun toString(): String {
        return "<AudioSource protocol=${protocol} stream_state=${getStreamState()}>"
    }

    fun dump(): String {
        return toString()
    }
}
