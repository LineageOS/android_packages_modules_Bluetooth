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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothLeAudioCodecConfig
import android.bluetooth.BluetoothLeAudioContentMetadata
import android.bluetooth.BluetoothLeBroadcastSettings
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.IBluetoothLeAudioCallback
import android.bluetooth.IBluetoothLeBroadcastCallback
import android.content.AttributionSource
import android.os.ParcelUuid
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** Test cases for [LeAudioServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class LeAudioServiceBinderTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: LeAudioService

    private lateinit var binder: LeAudioServiceBinder

    @Before
    fun setUp() {
        binder = LeAudioServiceBinder(service)
    }

    @Test
    fun connect() {
        val device = getTestDevice(0)

        binder.connect(device, source)
        verify(service).connect(device)
    }

    @Test
    fun disconnect() {
        val device = getTestDevice(0)

        binder.disconnect(device, source)
        verify(service).disconnect(device)
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getConnectedGroupLeadDevice() {
        val groupId = 1

        binder.getConnectedGroupLeadDevice(groupId, source)
        verify(service).getConnectedGroupLeadDevice(groupId)
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_DISCONNECTED)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        val device = getTestDevice(0)

        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setActiveDevice() {
        val device = getTestDevice(0)

        binder.setActiveDevice(device, source)
        verify(service).setActiveDevice(device)
    }

    @Test
    fun setActiveDevice_withNullDevice_callsRemoveActiveDevice() {
        binder.setActiveDevice(null, source)
        verify(service).removeActiveDevice(true)
    }

    @Test
    fun getActiveDevices() {
        binder.getActiveDevices(source)
        verify(service).activeDevices
    }

    @Test
    fun getAudioLocation() {
        val device = getTestDevice(0)

        binder.getAudioLocation(device, source)
        verify(service).getAudioLocation(device)
    }

    @Test
    fun setConnectionPolicy() {
        val device = getTestDevice(0)
        val connectionPolicy = CONNECTION_POLICY_UNKNOWN

        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        val device = getTestDevice(0)

        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun setCcidInformation() {
        val uuid = ParcelUuid(UUID(0, 0))
        val ccid = 0
        val contextType = BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED

        binder.setCcidInformation(uuid, ccid, contextType, source)
        verify(service).setCcidInformation(uuid, ccid, contextType)
    }

    @Test
    fun getGroupId() {
        val device = getTestDevice(0)

        binder.getGroupId(device, source)
        verify(service).getGroupId(device)
    }

    @Test
    fun groupAddNode() {
        val groupId = 1
        val device = getTestDevice(0)

        binder.groupAddNode(groupId, device, source)
        verify(service).groupAddNode(groupId, device)
    }

    @Test
    fun setInCall() {
        val inCall = true

        binder.setInCall(inCall, source)
        verify(service).setInCall(inCall)
    }

    @Test
    fun setInactiveForHfpHandover() {
        val device = getTestDevice(0)

        binder.setInactiveForHfpHandover(device, source)
        verify(service).setInactiveForHfpHandover(device)
    }

    @Test
    fun groupRemoveNode() {
        val groupId = 1
        val device = getTestDevice(0)

        binder.groupRemoveNode(groupId, device, source)
        verify(service).groupRemoveNode(groupId, device)
    }

    @Test
    fun setVolume() {
        val volume = 3

        binder.setVolume(volume, source)
        verify(service).setVolume(volume)
    }

    @Test
    fun registerUnregisterCallback() {
        val callback = mock<IBluetoothLeAudioCallback>()

        binder.registerCallback(callback, source)
        verify(service).registerCallback(callback)

        binder.unregisterCallback(callback, source)
        verify(service).unregisterCallback(callback)
    }

    @Test
    fun registerUnregisterLeBroadcastCallback() {
        val callback = mock<IBluetoothLeBroadcastCallback>()

        binder.registerLeBroadcastCallback(callback, source)
        verify(service).registerLeBroadcastCallback(callback)

        binder.unregisterLeBroadcastCallback(callback, source)
        verify(service).unregisterLeBroadcastCallback(callback)
    }

    @Test
    fun startBroadcast() {
        val broadcastSettings = buildBroadcastSettingsFromMetadata()

        binder.startBroadcast(broadcastSettings, source)
        verify(service).createBroadcast(broadcastSettings)
    }

    @Test
    fun stopBroadcast() {
        val id = 1

        binder.stopBroadcast(id, source)
        verify(service).stopBroadcast(id)
    }

    @Test
    fun updateBroadcast() {
        val id = 1
        val broadcastSettings = buildBroadcastSettingsFromMetadata()

        binder.updateBroadcast(id, broadcastSettings, source)
        verify(service).updateBroadcast(id, broadcastSettings)
    }

    @Test
    fun isPlaying() {
        val id = 1

        binder.isPlaying(id, source)
        verify(service).isPlaying(id)
    }

    @Test
    fun getAllBroadcastMetadata() {
        binder.getAllBroadcastMetadata(source)
        verify(service).allBroadcastMetadata
    }

    @Test
    fun getMaximumNumberOfBroadcasts() {
        binder.maximumNumberOfBroadcasts
        verify(service).maximumNumberOfBroadcasts
    }

    @Test
    fun getMaximumStreamsPerBroadcast() {
        binder.maximumStreamsPerBroadcast
        verify(service).maximumStreamsPerBroadcast
    }

    @Test
    fun getMaximumSubgroupsPerBroadcast() {
        binder.maximumSubgroupsPerBroadcast
        verify(service).maximumSubgroupsPerBroadcast
    }

    @Test
    fun getCodecStatus() {
        val groupId = 1

        binder.getCodecStatus(groupId, source)
        verify(service).getCodecStatus(groupId)
    }

    @Test
    fun setCodecConfigPreference() {
        val groupId = 1
        val inputConfig = BluetoothLeAudioCodecConfig.Builder().build()
        val outputConfig = BluetoothLeAudioCodecConfig.Builder().build()

        binder.setCodecConfigPreference(groupId, inputConfig, outputConfig, source)
        verify(service).setCodecConfigPreference(groupId, inputConfig, outputConfig)
    }

    private fun buildBroadcastSettingsFromMetadata(): BluetoothLeBroadcastSettings {
        val metadata = BluetoothLeAudioContentMetadata.Builder().build()

        val publicBroadcastMetadata = BluetoothLeAudioContentMetadata.Builder().build()

        val subgroupBuilder =
            BluetoothLeBroadcastSubgroupSettings.Builder()
                .setPreferredQuality(TEST_QUALITY)
                .setContentMetadata(metadata)

        return BluetoothLeBroadcastSettings.Builder()
            .setPublicBroadcast(false)
            .setBroadcastName(TEST_BROADCAST_NAME)
            .setBroadcastCode(null)
            .setPublicBroadcastMetadata(publicBroadcastMetadata)
            .addSubgroupSettings(subgroupBuilder.build())
            .build()
    }

    companion object {
        private const val TEST_BROADCAST_NAME = "TEST"
        private const val TEST_QUALITY = BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD
    }
}
