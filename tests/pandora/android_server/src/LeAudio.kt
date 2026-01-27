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

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothLeAudioCodecStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import pandora.LeAudioGrpc.LeAudioImplBase
import pandora.LeAudioProto.*

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
    private var mediaRecorder: MediaRecorder? = null

    private sealed class LeAudioCallbackEvent {
        data class CodecConfigChanged(val groupId: Int, val status: BluetoothLeAudioCodecStatus) :
            LeAudioCallbackEvent()

        data class GroupNodeAdded(val device: BluetoothDevice, val groupId: Int) :
            LeAudioCallbackEvent()

        data class GroupNodeRemoved(val device: BluetoothDevice, val groupId: Int) :
            LeAudioCallbackEvent()

        data class GroupStatusChanged(val groupId: Int, val groupStatus: Int) :
            LeAudioCallbackEvent()

        data class GroupStreamStatusChanged(val groupId: Int, val groupStreamStatus: Int) :
            LeAudioCallbackEvent()
    }

    init {
        scope = CoroutineScope(Dispatchers.Default)
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED)

        flow = intentFlow(context, intentFilter, scope).shareIn(scope, SharingStarted.Eagerly)
    }

    private val mCallbackEvents =
        callbackFlow {
                val callback =
                    object : BluetoothLeAudio.Callback {
                        override fun onCodecConfigChanged(
                            groupId: Int,
                            status: BluetoothLeAudioCodecStatus,
                        ) {
                            Log.i(TAG, "onCodecConfigChanged($groupId, $status)")
                            trySend(LeAudioCallbackEvent.CodecConfigChanged(groupId, status))
                        }

                        override fun onGroupNodeAdded(device: BluetoothDevice, groupId: Int) {
                            Log.i(TAG, "onGroupNodeAdded($device, $groupId")
                            trySend(LeAudioCallbackEvent.GroupNodeAdded(device, groupId))
                        }

                        override fun onGroupNodeRemoved(device: BluetoothDevice, groupId: Int) {
                            Log.i(TAG, "onGroupNodeRemoved($device, $groupId)")
                            trySend(LeAudioCallbackEvent.GroupNodeRemoved(device, groupId))
                        }

                        override fun onGroupStatusChanged(groupId: Int, groupStatus: Int) {
                            Log.i(TAG, "onGroupStatusChanged($groupId, $groupStatus)")
                            trySend(LeAudioCallbackEvent.GroupStatusChanged(groupId, groupStatus))
                        }

                        override fun onGroupStreamStatusChanged(
                            groupId: Int,
                            groupStreamStatus: Int,
                        ) {
                            Log.i(TAG, "onGroupStreamStatusChanged($groupId, $groupStreamStatus)")
                            trySend(
                                LeAudioCallbackEvent.GroupStreamStatusChanged(
                                    groupId,
                                    groupStreamStatus,
                                )
                            )
                        }
                    }
                bluetoothLeAudio.registerCallback(context.mainExecutor, callback)

                awaitClose { bluetoothLeAudio.unregisterCallback(callback) }
            }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    fun mapAudioUsage(audioUsage: AudioUsage): Int {
        return when (audioUsage) {
            AudioUsage.AUDIO_USAGE_MEDIA -> AudioAttributes.USAGE_MEDIA
            AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING ->
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            AudioUsage.AUDIO_USAGE_ALARM -> AudioAttributes.USAGE_ALARM
            AudioUsage.AUDIO_USAGE_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
            AudioUsage.AUDIO_USAGE_NOTIFICATION_RINGTONE ->
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            AudioUsage.AUDIO_USAGE_NOTIFICATION_COMMUNICATION_REQUEST ->
                AudioAttributes.USAGE_NOTIFICATION
            AudioUsage.AUDIO_USAGE_NOTIFICATION_COMMUNICATION_INSTANT ->
                AudioAttributes.USAGE_NOTIFICATION
            AudioUsage.AUDIO_USAGE_NOTIFICATION_COMMUNICATION_DELAYED ->
                AudioAttributes.USAGE_NOTIFICATION
            AudioUsage.AUDIO_USAGE_NOTIFICATION_EVENT -> AudioAttributes.USAGE_NOTIFICATION_EVENT
            AudioUsage.AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY ->
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            AudioUsage.AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ->
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioUsage.AUDIO_USAGE_ASSISTANCE_SONIFICATION ->
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            AudioUsage.AUDIO_USAGE_GAME -> AudioAttributes.USAGE_GAME
            AudioUsage.AUDIO_USAGE_VIRTUAL_SOURCE -> AudioAttributes.USAGE_VIRTUAL_SOURCE
            AudioUsage.AUDIO_USAGE_ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioUsage.AUDIO_USAGE_CALL_ASSISTANT -> AudioAttributes.USAGE_CALL_ASSISTANT
            else -> AudioAttributes.USAGE_UNKNOWN
        }
    }

    fun mapAudioSource(audioSource: AudioSource): Int {
        return when (audioSource) {
            AudioSource.AUDIO_SOURCE_DEFAULT -> MediaRecorder.AudioSource.DEFAULT
            AudioSource.AUDIO_SOURCE_MIC -> MediaRecorder.AudioSource.MIC
            AudioSource.AUDIO_SOURCE_VOICE_UPLINK -> MediaRecorder.AudioSource.VOICE_UPLINK
            AudioSource.AUDIO_SOURCE_VOICE_DOWNLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
            AudioSource.AUDIO_SOURCE_VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
            AudioSource.AUDIO_SOURCE_CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            AudioSource.AUDIO_SOURCE_VOICE_RECOGNITION ->
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSource.AUDIO_SOURCE_VOICE_COMMUNICATION ->
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            AudioSource.AUDIO_SOURCE_REMOTE_SUBMIX -> MediaRecorder.AudioSource.REMOTE_SUBMIX
            AudioSource.AUDIO_SOURCE_UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            AudioSource.AUDIO_SOURCE_VOICE_PERFORMANCE ->
                MediaRecorder.AudioSource.VOICE_PERFORMANCE
            else -> {
                MediaRecorder.AudioSource.DEFAULT
            }
        }
    }

    fun mapRingerMode(ringerMode: RingerMode): Int {
        return when (ringerMode) {
            RingerMode.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    }

    fun mapToProtoRingerMode(ringerMode: Int): RingerMode {
        return when (ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.RINGER_MODE_NORMAL
            AudioManager.RINGER_MODE_SILENT -> RingerMode.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.RINGER_MODE_VIBRATE
            else -> RingerMode.RINGER_MODE_NORMAL
        }
    }

    fun GroupStreamStatus.toAndroidStatus(): Int {
        return when (this) {
            GroupStreamStatus.GROUP_STREAM_STATUS_IDLE -> BluetoothLeAudio.GROUP_STREAM_STATUS_IDLE
            GroupStreamStatus.GROUP_STREAM_STATUS_STREAMING ->
                BluetoothLeAudio.GROUP_STREAM_STATUS_STREAMING
            else -> {
                BluetoothLeAudio.GROUP_STREAM_STATUS_IDLE
            }
        }
    }

    override fun close() {
        audioTrack?.release()
        audioTrack = null
        mediaRecorder?.release()
        mediaRecorder = null
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

    override fun leAudioStart(
        request: LeAudioStartRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        val audioUsage: AudioUsage = request.audioUsage
        val metadataTag: String? = request.metadataTag
        grpcUnary<Empty>(scope, responseObserver) {
            if (audioTrack == null) {
                audioTrack =
                    buildAudioTrack(
                        audioUsage = mapAudioUsage(audioUsage),
                        metadataTag = metadataTag,
                    )
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

    override fun leAudioStopRecorder(
        request: LeAudioStopRecorderRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            mediaRecorder?.let {
                it.stop()
                it.release()
            } ?: Log.i(TAG, "leAudioStopRecorder: Cannot stop, MediaRecorder is null")
            mediaRecorder = null

            Empty.getDefaultInstance()
        }
    }

    override fun leAudioPrepareRecorder(
        request: LeAudioPrepareRecorderRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        val audioSource: AudioSource = request.audioSource
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "leAudioPrepareRecorder: device=$device")

            if (bluetoothLeAudio.getConnectionState(device) != BluetoothLeAudio.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot start")
            }
            bluetoothLeAudio.setActiveDevice(device)
            flow
                .filter { it.action == BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED }
                .filter { it.getBluetoothDeviceExtra() == device }
                .first()
            Log.i(TAG, "leAudioPrepareRecorder: Active device changed to $device")

            val filePath =
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
                    (Path(it.absolutePath) / "recording.amr").toString()
                }
                    ?: throw IllegalStateException(
                        "External music directory not found, cannot create recording path."
                    )

            Log.i(
                TAG,
                "leAudioPrepareRecorder: initializing media recorder. Recorded file path: $filePath",
            )
            mediaRecorder =
                MediaRecorder(context).apply {
                    setAudioSource(mapAudioSource(audioSource))
                    setAudioSamplingRate(16000)
                    setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
                    setOutputFile(filePath)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
                    prepare()
                }
            Log.i(TAG, "leAudioPrepareRecorder: MediaRecorder prepared")

            Empty.getDefaultInstance()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun leAudioCaptureAudio(
        request: LeAudioCaptureAudioRequest,
        responseObserver: StreamObserver<LeAudioCaptureAudioResponse>,
    ) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.i(TAG, "leAudioCaptureAudio: Number of available input devices: ${devices.size}")

        for (device in devices) {
            Log.i(TAG, "leAudioCaptureAudio: Available device type: ${device.type}")
            if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                Log.i(
                    TAG,
                    "leAudioCaptureAudio: BLE_HEADSET found. Setting preferred device: ${device.address}",
                )
                mediaRecorder?.let {
                    it.preferredDevice = device
                    it.start()
                }
                    ?: Log.i(
                        TAG,
                        "leAudioCaptureAudio: Cannot set preferred device, MediaRecorder is null",
                    )
            } else {
                Log.i(TAG, "leAudioCaptureAudio: Skipping (not a BLE_HEADSET device).")
            }
        }

        responseObserver.onNext(LeAudioCaptureAudioResponse.getDefaultInstance())
        responseObserver.onCompleted()
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

    override fun leAudioWaitGroupStreamStatusChanged(
        request: LeAudioWaitGroupStreamStatusChangedRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            val groupId: Int = bluetoothLeAudio.getGroupId(device)
            val groupStreamStatus: GroupStreamStatus = request.groupStreamStatus
            Log.i(
                TAG,
                "waitLeAudioGroupStreamStatusChanged: device=$device, groupId=${groupId}, groupStreamStatus=${groupStreamStatus}",
            )
            mCallbackEvents
                .filter {
                    it is LeAudioCallbackEvent.GroupStreamStatusChanged &&
                        it.groupId == groupId &&
                        it.groupStreamStatus == groupStreamStatus.toAndroidStatus()
                }
                .first()
            Empty.getDefaultInstance()
        }
    }

    override fun leAudioGetRingerMode(
        request: Empty,
        responseObserver: StreamObserver<LeAudioGetRingerModeResponse>,
    ) {
        grpcUnary<LeAudioGetRingerModeResponse>(scope, responseObserver) {
            val ringerMode = mapToProtoRingerMode(audioManager.ringerMode)
            Log.i(TAG, "getRingerMode: $ringerMode")
            LeAudioGetRingerModeResponse.newBuilder().setRingerMode(ringerMode).build()
        }
    }

    override fun leAudioSetRingerMode(
        request: LeAudioSetRingerModeRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "setRingerMode: ${request.ringerMode}")
            audioManager.ringerMode = mapRingerMode(request.ringerMode)
            Empty.getDefaultInstance()
        }
    }
}
