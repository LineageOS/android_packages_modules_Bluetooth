/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.pandora

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioTrack
import android.media.AudioManager
import android.util.Log
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import pandora.LeAudioGrpc.LeAudioImplBase
import pandora.LeAudioProto.*
import java.io.PrintWriter
import java.io.StringWriter

@kotlinx.coroutines.ExperimentalCoroutinesApi
class LeAudio(val context: Context) : LeAudioImplBase(), Closeable {

    private val TAG = "PandoraLeAudio"

    private val scope: CoroutineScope
    private val flow: Flow<Intent>

    private val audioManager = context.getSystemService(AudioManager::class.java)!!

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeAudio =
        getProfileProxy<BluetoothLeAudio>(context, BluetoothProfile.LE_AUDIO)

    private var audioTrack: AudioTrack? = null

    init {
        scope = CoroutineScope(Dispatchers.Default)
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)

        flow = intentFlow(context, intentFilter, scope).shareIn(scope, SharingStarted.Eagerly)
    }

    override fun close() {
        bluetoothAdapter.closeProfileProxy(BluetoothProfile.LE_AUDIO, bluetoothLeAudio)
        scope.cancel()
    }

    override fun open(request: OpenRequest, responseObserver: StreamObserver<Empty>) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "open: device=$device")

            if (bluetoothLeAudio.getConnectionState(device) != STATE_CONNECTED) {
                bluetoothLeAudio.connect(device)
                val state =
                    flow
                        .filter {
                            it.getAction() ==
                                BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED
                        }
                        .filter { it.getBluetoothDeviceExtra() == device }
                        .map {
                            it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
                        }
                        .filter { it == STATE_CONNECTED || it == STATE_DISCONNECTED }
                        .first()

                if (state == STATE_DISCONNECTED) {
                    throw RuntimeException("open failed, LE_AUDIO has been disconnected")
                }
            }

            Empty.getDefaultInstance()
        }
    }

    override fun leAudioStart(request: LeAudioStartRequest, responseObserver: StreamObserver<Empty>) {
        grpcUnary<Empty>(scope, responseObserver) {
            if (audioTrack == null) {
                audioTrack = buildAudioTrack()
            }
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "start: device=$device")

            if (bluetoothLeAudio.getConnectionState(device) != BluetoothLeAudio.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot start")
            }

            // Configure the selected device as active device if it is not
            // already.
            bluetoothLeAudio.setActiveDevice(device)

            // Play an audio track.
            audioTrack!!.play()

            Empty.getDefaultInstance()
        }
    }

    override fun leAudioStop(request: LeAudioStopRequest, responseObserver: StreamObserver<Empty>) {
        grpcUnary<Empty>(scope, responseObserver) {
            checkNotNull(audioTrack) { "No track to pause!" }

            // Play an audio track.
            audioTrack!!.pause()

            Empty.getDefaultInstance()
        }
    }

    override fun leAudioPlaybackAudio(
        responseObserver: StreamObserver<LeAudioPlaybackAudioResponse>
    ): StreamObserver<LeAudioPlaybackAudioRequest> {
        Log.i(TAG, "leAudioPlaybackAudio")

        if (audioTrack!!.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            responseObserver.onError(
                Status.UNKNOWN.withDescription("AudioTrack is not started").asException()
            )
        }

        // Volume is maxed out to avoid any amplitude modification of the provided audio data,
        // enabling the test runner to do comparisons between input and output audio signal.
        // Any volume modification should be done before providing the audio data.
        if (audioManager.isVolumeFixed) {
            Log.w(TAG, "Volume is fixed, cannot max out the volume")
        } else {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVolume) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    maxVolume,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
        }

        return object : StreamObserver<LeAudioPlaybackAudioRequest> {
            override fun onNext(request: LeAudioPlaybackAudioRequest) {
                val data = request.data.toByteArray()
                val written = synchronized(audioTrack!!) { audioTrack!!.write(data, 0, data.size) }
                if (written != data.size) {
                    responseObserver.onError(
                        Status.UNKNOWN.withDescription("AudioTrack write failed").asException()
                    )
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                responseObserver.onError(
                    Status.UNKNOWN.withCause(t).withDescription(sw.toString()).asException()
                )
            }

            override fun onCompleted() {
                responseObserver.onNext(LeAudioPlaybackAudioResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
    }
}
