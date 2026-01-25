/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.storage

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudioCodecConfig
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.getProfileName
import android.bluetooth.BluetoothSinkAudioPolicy
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.storage.ActiveAudioPolicy.Type as ActiveAudioPolicy
import com.android.bluetooth.storage.HfpClientSettings.SinkAudioPolicy
import com.android.bluetooth.storage.MediaProfile.Type as MediaProfile
import com.android.bluetooth.storage.VoiceProfile.Type as VoiceProfile

private const val TAG = "BluetoothStorageMapping"

// This file contains the mapping logic between Android framework constants and the storage proto.
// It is designed to keep the main BluetoothStorageManager clean and focused on its core logic.

internal fun AdapterService.getDeviceFromAddress(address: String) =
    try {
        getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid address found in storage: $address", e)
        null
    }

internal fun toMediaProfile(profile: Int) =
    when (profile) {
        BluetoothProfile.A2DP -> MediaProfile.A2DP
        BluetoothProfile.LE_AUDIO -> MediaProfile.LE_AUDIO
        else -> MediaProfile.UNKNOWN
    }

internal fun toVoiceProfile(profile: Int) =
    when (profile) {
        BluetoothProfile.HEADSET -> VoiceProfile.HFP
        BluetoothProfile.LE_AUDIO -> VoiceProfile.LE_AUDIO
        else -> VoiceProfile.UNKNOWN
    }

internal fun fromMediaProfile(profile: MediaProfile) =
    when (profile) {
        MediaProfile.A2DP -> BluetoothProfile.A2DP
        MediaProfile.LE_AUDIO -> BluetoothProfile.LE_AUDIO
        else -> 0
    }

internal fun fromVoiceProfile(profile: VoiceProfile) =
    when (profile) {
        VoiceProfile.HFP -> BluetoothProfile.HEADSET
        VoiceProfile.LE_AUDIO -> BluetoothProfile.LE_AUDIO
        else -> 0
    }

internal fun toSinkAudioPolicy(policy: SinkAudioPolicy) =
    when (policy) {
        SinkAudioPolicy.ALLOWED -> BluetoothSinkAudioPolicy.POLICY_ALLOWED
        SinkAudioPolicy.NOT_ALLOWED -> BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED
        else -> BluetoothSinkAudioPolicy.POLICY_UNCONFIGURED
    }

internal fun fromSinkAudioPolicy(policy: Int) =
    when (policy) {
        BluetoothSinkAudioPolicy.POLICY_ALLOWED -> SinkAudioPolicy.ALLOWED
        BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED -> SinkAudioPolicy.NOT_ALLOWED
        else -> SinkAudioPolicy.UNCONFIGURED
    }

internal fun toSupported(status: Boolean?) =
    when (status) {
        true -> BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED
        false -> BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED
        null -> BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN
    }

internal fun fromSupported(value: Int) =
    when (value) {
        BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED -> true
        BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED -> false
        else -> null
    }

internal fun toPreference(status: Boolean?) =
    when (status) {
        true -> BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED
        false -> BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
        null -> BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN
    }

internal fun fromPreference(value: Int) =
    when (value) {
        BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED -> true
        BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED -> false
        else -> null
    }

internal fun toAccess(status: Boolean?) =
    when (status) {
        true -> BluetoothDevice.ACCESS_ALLOWED
        false -> BluetoothDevice.ACCESS_REJECTED
        null -> BluetoothDevice.ACCESS_UNKNOWN
    }

internal fun fromAccess(value: Int) =
    when (value) {
        BluetoothDevice.ACCESS_ALLOWED -> true
        BluetoothDevice.ACCESS_REJECTED -> false
        else -> null
    }

internal fun fromProtoCodecConfig(proto: CodecConfig) =
    BluetoothLeAudioCodecConfig.Builder()
        .setCodecType(proto.type)
        .setCodecPriority(proto.priority)
        .setSampleRate(proto.sampleRate)
        .setBitsPerSample(proto.bitsPerSample)
        .setChannelCount(proto.channelCount)
        .setFrameDuration(proto.frameDuration)
        .setOctetsPerFrame(proto.octetsPerFrame)
        .setMinOctetsPerFrame(proto.minOctetsPerFrame)
        .setMaxOctetsPerFrame(proto.maxOctetsPerFrame)
        .build()

internal fun toProtoCodecConfig(config: BluetoothLeAudioCodecConfig) =
    CodecConfig.newBuilder()
        .setType(config.codecType)
        .setPriority(config.codecPriority)
        .setSampleRate(config.sampleRate)
        .setBitsPerSample(config.bitsPerSample)
        .setChannelCount(config.channelCount)
        .setFrameDuration(config.frameDuration)
        .setOctetsPerFrame(config.octetsPerFrame)
        .setMinOctetsPerFrame(config.minOctetsPerFrame)
        .setMaxOctetsPerFrame(config.maxOctetsPerFrame)
        .build()

internal fun fromActiveAudioPolicy(policy: ActiveAudioPolicy.Type) =
    when (policy) {
        ActiveAudioPolicy.ACTIVE ->
            BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_ACTIVE_UPON_CONNECTION
        ActiveAudioPolicy.INACTIVE ->
            BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_INACTIVE_UPON_CONNECTION
        else -> BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT
    }

internal fun toActiveAudioPolicy(policy: Int) =
    when (policy) {
        BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_ACTIVE_UPON_CONNECTION ->
            ActiveAudioPolicy.ACTIVE
        BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_INACTIVE_UPON_CONNECTION ->
            ActiveAudioPolicy.INACTIVE
        else -> ActiveAudioPolicy.UNKNOWN
    }

internal fun fromConnectionPolicy(connectionPolicy: Int) =
    when (connectionPolicy) {
        CONNECTION_POLICY_UNKNOWN -> Policy.UNKNOWN
        CONNECTION_POLICY_FORBIDDEN -> Policy.FORBIDDEN
        CONNECTION_POLICY_ALLOWED -> Policy.ALLOWED
        else -> throw IllegalArgumentException("Invalid connection policy: $connectionPolicy")
    }

internal fun Policy.toConnectionPolicy() =
    when (this) {
        Policy.UNKNOWN -> CONNECTION_POLICY_UNKNOWN
        Policy.FORBIDDEN -> CONNECTION_POLICY_FORBIDDEN
        Policy.ALLOWED -> CONNECTION_POLICY_ALLOWED
        else -> throw IllegalArgumentException("Invalid policy: $this")
    }

/** A data class that holds lambdas for getting and setting a specific profile policy. */
internal data class ProfilePolicyAccessor(
    val getter: (ProfileConnectionPolicies) -> Policy,
    val setter: (ProfileConnectionPolicies.Builder, Policy) -> ProfileConnectionPolicies.Builder,
)

/** The single source of truth for mapping a profile constant to its proto field accessor. */
private val policyAccessorMap =
    mapOf(
        BluetoothProfile.A2DP to
            ProfilePolicyAccessor({ it.a2Dp }, { b, status -> b.setA2Dp(status) }),
        BluetoothProfile.A2DP_SINK to
            ProfilePolicyAccessor({ it.a2DpSink }, { b, status -> b.setA2DpSink(status) }),
        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT to
            ProfilePolicyAccessor({ it.bassClient }, { b, status -> b.setBassClient(status) }),
        BluetoothProfile.BATTERY to
            ProfilePolicyAccessor({ it.battery }, { b, status -> b.setBattery(status) }),
        BluetoothProfile.CSIP_SET_COORDINATOR to
            ProfilePolicyAccessor({ it.csip }, { b, status -> b.setCsip(status) }),
        BluetoothProfile.HAP_CLIENT to
            ProfilePolicyAccessor({ it.hap }, { b, status -> b.setHap(status) }),
        BluetoothProfile.HEARING_AID to
            ProfilePolicyAccessor({ it.hearingAid }, { b, status -> b.setHearingAid(status) }),
        BluetoothProfile.HEADSET to
            ProfilePolicyAccessor({ it.hfp }, { b, status -> b.setHfp(status) }),
        BluetoothProfile.HEADSET_CLIENT to
            ProfilePolicyAccessor({ it.hfpClient }, { b, status -> b.setHfpClient(status) }),
        BluetoothProfile.HID_HOST to
            ProfilePolicyAccessor({ it.hidHost }, { b, status -> b.setHidHost(status) }),
        BluetoothProfile.LE_AUDIO to
            ProfilePolicyAccessor({ it.leAudio }, { b, status -> b.setLeAudio(status) }),
        BluetoothProfile.LE_CALL_CONTROL to
            ProfilePolicyAccessor({ it.tbs }, { b, status -> b.setTbs(status) }),
        BluetoothProfile.MAP to
            ProfilePolicyAccessor({ it.map }, { b, status -> b.setMap(status) }),
        BluetoothProfile.MAP_CLIENT to
            ProfilePolicyAccessor({ it.mapClient }, { b, status -> b.setMapClient(status) }),
        BluetoothProfile.PAN to
            ProfilePolicyAccessor({ it.pan }, { b, status -> b.setPan(status) }),
        BluetoothProfile.PBAP to
            ProfilePolicyAccessor({ it.pbap }, { b, status -> b.setPbap(status) }),
        BluetoothProfile.PBAP_CLIENT to
            ProfilePolicyAccessor({ it.pbapClient }, { b, status -> b.setPbapClient(status) }),
        BluetoothProfile.SAP to
            ProfilePolicyAccessor({ it.sap }, { b, status -> b.setSap(status) }),
        BluetoothProfile.VOLUME_CONTROL to
            ProfilePolicyAccessor({ it.vcp }, { b, status -> b.setVcp(status) }),
        BluetoothProfile.MCP_CLIENT to
            ProfilePolicyAccessor({ it.mcpClient }, { b, status -> b.setMcpClient(status) }),
    )

/** Return accessor to the proto field for ProfileConnectionPolicies. */
internal fun getPolicyAccessor(profile: Int) =
    policyAccessorMap[profile]
        ?: throw IllegalArgumentException("Invalid profile: ${getProfileName(profile)}")
