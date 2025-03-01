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

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothCodecType
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.util.Log
import com.google.protobuf.BoolValue
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withTimeoutOrNull
import pandora.A2DPGrpc.A2DPImplBase
import pandora.A2DPProto.*

@kotlinx.coroutines.ExperimentalCoroutinesApi
class A2dp(val context: Context) : A2DPImplBase(), Closeable {
    private val TAG = "PandoraA2dp"

    private val scope: CoroutineScope
    private val flow: Flow<Intent>

    private val audioManager = context.getSystemService(AudioManager::class.java)!!

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothA2dp = getProfileProxy<BluetoothA2dp>(context, BluetoothProfile.A2DP)

    private var audioTrack: AudioTrack? = null

    init {
        scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        intentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED)

        flow = intentFlow(context, intentFilter, scope).shareIn(scope, SharingStarted.Eagerly)
    }

    override fun close() {
        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
        scope.cancel()
    }

    override fun openSource(
        request: OpenSourceRequest,
        responseObserver: StreamObserver<OpenSourceResponse>,
    ) {
        grpcUnary<OpenSourceResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "openSource: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                bluetoothA2dp.connect(device)
                val state =
                    flow
                        .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
                        .filter { it.getBluetoothDeviceExtra() == device }
                        .map {
                            it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
                        }
                        .filter { it == STATE_CONNECTED || it == STATE_DISCONNECTED }
                        .first()

                if (state == STATE_DISCONNECTED) {
                    throw RuntimeException("openSource failed, A2DP has been disconnected")
                }
            }

            val source =
                Source.newBuilder().setCookie(ByteString.copyFrom(device.getAddress(), "UTF-8"))
            OpenSourceResponse.newBuilder().setSource(source).build()
        }
    }

    override fun waitSource(
        request: WaitSourceRequest,
        responseObserver: StreamObserver<WaitSourceResponse>,
    ) {
        grpcUnary<WaitSourceResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "waitSource: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                val state =
                    flow
                        .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
                        .filter { it.getBluetoothDeviceExtra() == device }
                        .map {
                            it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
                        }
                        .filter { it == STATE_CONNECTED || it == STATE_DISCONNECTED }
                        .first()

                if (state == STATE_DISCONNECTED) {
                    throw RuntimeException("waitSource failed, A2DP has been disconnected")
                }
            }

            val source =
                Source.newBuilder().setCookie(ByteString.copyFrom(device.getAddress(), "UTF-8"))
            WaitSourceResponse.newBuilder().setSource(source).build()
        }
    }

    override fun start(request: StartRequest, responseObserver: StreamObserver<StartResponse>) {
        grpcUnary<StartResponse>(scope, responseObserver) {
            if (audioTrack == null) {
                audioTrack = buildAudioTrack()
            }
            val device = bluetoothAdapter.getRemoteDevice(request.source.cookie.toString("UTF-8"))
            Log.i(TAG, "start: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot start")
            }

            // Configure the selected device as active device if it is not
            // already.
            bluetoothA2dp.setActiveDevice(device)

            // Play an audio track.
            audioTrack!!.play()

            // If A2dp is not already playing, wait for it
            if (!bluetoothA2dp.isA2dpPlaying(device)) {
                flow
                    .filter { it.getAction() == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == device }
                    .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }
                    .filter { it == BluetoothA2dp.STATE_PLAYING }
                    .first()
            }
            StartResponse.getDefaultInstance()
        }
    }

    override fun suspend(
        request: SuspendRequest,
        responseObserver: StreamObserver<SuspendResponse>,
    ) {
        grpcUnary<SuspendResponse>(scope, responseObserver) {
            val device = bluetoothAdapter.getRemoteDevice(request.source.cookie.toString("UTF-8"))
            val timeoutMillis: Duration = 5000.milliseconds

            Log.i(TAG, "suspend: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot suspend")
            }

            if (!bluetoothA2dp.isA2dpPlaying(device)) {
                throw RuntimeException("Device is already suspended, cannot suspend")
            }

            val a2dpPlayingStateFlow =
                flow
                    .filter { it.getAction() == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == device }
                    .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }

            audioTrack!!.pause()
            withTimeoutOrNull(timeoutMillis) {
                a2dpPlayingStateFlow.filter { it == BluetoothA2dp.STATE_NOT_PLAYING }.first()
            }
            SuspendResponse.getDefaultInstance()
        }
    }

    override fun isSuspended(
        request: IsSuspendedRequest,
        responseObserver: StreamObserver<BoolValue>,
    ) {
        grpcUnary(scope, responseObserver) {
            val device = bluetoothAdapter.getRemoteDevice(request.source.cookie.toString("UTF-8"))
            Log.i(TAG, "isSuspended: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot get suspend state")
            }

            val isSuspended = bluetoothA2dp.isA2dpPlaying(device)

            BoolValue.newBuilder().setValue(isSuspended).build()
        }
    }

    override fun close(request: CloseRequest, responseObserver: StreamObserver<CloseResponse>) {
        grpcUnary<CloseResponse>(scope, responseObserver) {
            val device = bluetoothAdapter.getRemoteDevice(request.source.cookie.toString("UTF-8"))
            Log.i(TAG, "close: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot close")
            }

            val a2dpConnectionStateChangedFlow =
                flow
                    .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == device }
                    .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }

            bluetoothA2dp.disconnect(device)
            a2dpConnectionStateChangedFlow.filter { it == BluetoothA2dp.STATE_DISCONNECTED }.first()

            CloseResponse.getDefaultInstance()
        }
    }

    override fun playbackAudio(
        responseObserver: StreamObserver<PlaybackAudioResponse>
    ): StreamObserver<PlaybackAudioRequest> {
        Log.i(TAG, "playbackAudio")

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

        return object : StreamObserver<PlaybackAudioRequest> {
            override fun onNext(request: PlaybackAudioRequest) {
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
                responseObserver.onNext(PlaybackAudioResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
    }

    override fun getAudioEncoding(
        request: GetAudioEncodingRequest,
        responseObserver: StreamObserver<GetAudioEncodingResponse>,
    ) {
        grpcUnary<GetAudioEncodingResponse>(scope, responseObserver) {
            val device = bluetoothAdapter.getRemoteDevice(request.source.cookie.toString("UTF-8"))
            Log.i(TAG, "getAudioEncoding: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot getAudioEncoding")
            }

            // For now, we only support 44100 kHz sampling rate.
            GetAudioEncodingResponse.newBuilder()
                .setEncoding(AudioEncoding.PCM_S16_LE_44K1_STEREO)
                .build()
        }
    }

    override fun getConfiguration(
        request: GetConfigurationRequest,
        responseObserver: StreamObserver<GetConfigurationResponse>,
    ) {
        grpcUnary<GetConfigurationResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "getConfiguration: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot getConfiguration")
            }

            val codecStatus = bluetoothA2dp.getCodecStatus(device)
            if (codecStatus == null) {
                throw RuntimeException("Codec status is null")
            }

            val currentCodecConfig = codecStatus.getCodecConfig()
            if (currentCodecConfig == null) {
                throw RuntimeException("Codec configuration is null")
            }

            val supportedCodecTypes = bluetoothA2dp.getSupportedCodecTypes()
            val configuration =
                Configuration.newBuilder()
                    .setId(getProtoCodecId(currentCodecConfig, supportedCodecTypes))
                    .setParameters(getProtoCodecParameters(currentCodecConfig))
                    .build()
            GetConfigurationResponse.newBuilder().setConfiguration(configuration).build()
        }
    }

    override fun setConfiguration(
        request: SetConfigurationRequest,
        responseObserver: StreamObserver<SetConfigurationResponse>,
    ) {
        grpcUnary<SetConfigurationResponse>(scope, responseObserver) {
            val timeoutMillis: Duration = 5000.milliseconds
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "setConfiguration: device=$device")

            if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
                throw RuntimeException("Device is not connected, cannot getCodecStatus")
            }

            val newCodecConfig = getCodecConfigFromProtoConfiguration(request.configuration)
            if (newCodecConfig == null) {
                throw RuntimeException("New codec configuration is null")
            }

            val codecId = packCodecId(request.configuration.id)

            val a2dpCodecConfigChangedFlow =
                flow
                    .filter { it.getAction() == BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == device }
                    .map {
                        it.getParcelableExtra(
                                BluetoothCodecStatus.EXTRA_CODEC_STATUS,
                                BluetoothCodecStatus::class.java,
                            )
                            ?.getCodecConfig()
                    }

            bluetoothA2dp.setCodecConfigPreference(device, newCodecConfig)

            val result =
                withTimeoutOrNull(timeoutMillis) {
                    a2dpCodecConfigChangedFlow
                        .filter { it?.getExtendedCodecType()?.getCodecId() == codecId }
                        .first()
                }
            Log.i(TAG, "Result=$result")
            SetConfigurationResponse.newBuilder().setSuccess(result != null).build()
        }
    }

    private fun unpackCodecId(codecId: Long): CodecId {
        val codecType = (codecId and 0xFF).toInt()
        val vendorId = ((codecId shr 8) and 0xFFFF).toInt()
        val vendorCodecId = ((codecId shr 24) and 0xFFFF).toInt()
        val codecIdBuilder = CodecId.newBuilder()
        when (codecType) {
            0x00 -> {
                codecIdBuilder.setSbc(Empty.getDefaultInstance())
            }
            0x02 -> {
                codecIdBuilder.setMpegAac(Empty.getDefaultInstance())
            }
            0xFF -> {
                val vendor = Vendor.newBuilder().setId(vendorId).setCodecId(vendorCodecId).build()
                codecIdBuilder.setVendor(vendor)
            }
            else -> {
                throw RuntimeException("Unknown codec type")
            }
        }
        return codecIdBuilder.build()
    }

    private fun packCodecId(codecId: CodecId): Long {
        var codecType: Int
        var vendorId: Int = 0
        var vendorCodecId: Int = 0
        when {
            codecId.hasSbc() -> {
                codecType = 0x00
            }
            codecId.hasMpegAac() -> {
                codecType = 0x02
            }
            codecId.hasVendor() -> {
                codecType = 0xFF
                vendorId = codecId.vendor.id
                vendorCodecId = codecId.vendor.codecId
            }
            else -> {
                throw RuntimeException("Unknown codec type")
            }
        }
        return (codecType.toLong() and 0xFF) or
            ((vendorId.toLong() and 0xFFFF) shl 8) or
            ((vendorCodecId.toLong() and 0xFFFF) shl 24)
    }

    private fun getProtoCodecId(
        codecConfig: BluetoothCodecConfig,
        supportedCodecTypes: Collection<BluetoothCodecType>,
    ): CodecId {
        var selectedCodecType: BluetoothCodecType? = null
        for (codecType: BluetoothCodecType in supportedCodecTypes) {
            if (codecType.getCodecId() == codecConfig.getExtendedCodecType()?.getCodecId()) {
                selectedCodecType = codecType
            }
        }
        if (selectedCodecType == null) {
            Log.e(TAG, "getProtoCodecId: selectedCodecType is null")
            return CodecId.newBuilder().build()
        }
        return unpackCodecId(selectedCodecType.getCodecId())
    }

    private fun getProtoCodecParameters(codecConfig: BluetoothCodecConfig): CodecParameters {
        var channelMode: ChannelMode
        var samplingFrequencyHz: Int
        var bitDepth: Int
        when (codecConfig.getSampleRate()) {
            BluetoothCodecConfig.SAMPLE_RATE_NONE -> {
                samplingFrequencyHz = 0
            }
            BluetoothCodecConfig.SAMPLE_RATE_44100 -> {
                samplingFrequencyHz = 44100
            }
            BluetoothCodecConfig.SAMPLE_RATE_48000 -> {
                samplingFrequencyHz = 48000
            }
            BluetoothCodecConfig.SAMPLE_RATE_88200 -> {
                samplingFrequencyHz = 88200
            }
            BluetoothCodecConfig.SAMPLE_RATE_96000 -> {
                samplingFrequencyHz = 96000
            }
            BluetoothCodecConfig.SAMPLE_RATE_176400 -> {
                samplingFrequencyHz = 176400
            }
            BluetoothCodecConfig.SAMPLE_RATE_192000 -> {
                samplingFrequencyHz = 192000
            }
            else -> {
                throw RuntimeException("Unknown sample rate")
            }
        }
        when (codecConfig.getBitsPerSample()) {
            BluetoothCodecConfig.BITS_PER_SAMPLE_NONE -> {
                bitDepth = 0
            }
            BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> {
                bitDepth = 16
            }
            BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> {
                bitDepth = 24
            }
            BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> {
                bitDepth = 32
            }
            else -> {
                throw RuntimeException("Unknown bit depth")
            }
        }
        when (codecConfig.getChannelMode()) {
            BluetoothCodecConfig.CHANNEL_MODE_NONE -> {
                channelMode = ChannelMode.UNKNOWN
            }
            BluetoothCodecConfig.CHANNEL_MODE_MONO -> {
                channelMode = ChannelMode.MONO
            }
            BluetoothCodecConfig.CHANNEL_MODE_STEREO -> {
                channelMode = ChannelMode.STEREO
            }
            else -> {
                throw RuntimeException("Unknown channel mode")
            }
        }
        return CodecParameters.newBuilder()
            .setSamplingFrequencyHz(samplingFrequencyHz)
            .setBitDepth(bitDepth)
            .setChannelMode(channelMode)
            .build()
    }

    private fun getCodecConfigFromProtoConfiguration(
        configuration: Configuration
    ): BluetoothCodecConfig? {
        var selectedCodecType: BluetoothCodecType? = null
        val codecTypes = bluetoothA2dp.getSupportedCodecTypes()
        val codecId = packCodecId(configuration.id)
        var sampleRate: Int
        var bitsPerSample: Int
        var channelMode: Int
        for (codecType: BluetoothCodecType in codecTypes) {
            if (codecType.getCodecId() == codecId) {
                selectedCodecType = codecType
            }
        }
        if (selectedCodecType == null) {
            Log.e(TAG, "getCodecConfigFromProtoConfiguration: selectedCodecType is null")
            return null
        }
        when (configuration.parameters.getSamplingFrequencyHz()) {
            0 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_NONE
            }
            44100 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_44100
            }
            48000 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_48000
            }
            88200 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_88200
            }
            96000 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_96000
            }
            176400 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_176400
            }
            192000 -> {
                sampleRate = BluetoothCodecConfig.SAMPLE_RATE_192000
            }
            else -> {
                throw RuntimeException("Unknown sample rate")
            }
        }
        when (configuration.parameters.getBitDepth()) {
            0 -> {
                bitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_NONE
            }
            16 -> {
                bitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_16
            }
            24 -> {
                bitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_24
            }
            32 -> {
                bitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_32
            }
            else -> {
                throw RuntimeException("Unknown bit depth")
            }
        }
        when (configuration.parameters.getChannelMode()) {
            ChannelMode.UNKNOWN -> {
                channelMode = BluetoothCodecConfig.CHANNEL_MODE_NONE
            }
            ChannelMode.MONO -> {
                channelMode = BluetoothCodecConfig.CHANNEL_MODE_MONO
            }
            ChannelMode.STEREO -> {
                channelMode = BluetoothCodecConfig.CHANNEL_MODE_STEREO
            }
            else -> {
                throw RuntimeException("Unknown channel mode")
            }
        }
        return BluetoothCodecConfig.Builder()
            .setExtendedCodecType(selectedCodecType)
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(sampleRate)
            .setBitsPerSample(bitsPerSample)
            .setChannelMode(channelMode)
            .build()
    }
}
