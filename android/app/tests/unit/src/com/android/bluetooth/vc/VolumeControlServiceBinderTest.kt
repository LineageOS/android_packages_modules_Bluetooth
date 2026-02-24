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

package com.android.bluetooth.vc

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.IAudioInputCallback
import android.content.AttributionSource
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import java.util.function.Consumer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [VolumeControlServiceBinder]. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class VolumeControlServiceBinderTest(flags: FlagsWrapper) {
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: VolumeControlService

    private val device = getTestDevice(25)

    private lateinit var binder: VolumeControlServiceBinder

    @Before
    fun setUp() {
        doReturn(true).whenever(service).isAvailable
        doAnswer { inv ->
                (inv.getArgument(0) as Consumer<VolumeControlService>).accept(service)
                null
            }
            .whenever(service)
            .post(any())

        binder = VolumeControlServiceBinder(service)
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED

        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun isVolumeOffsetAvailable() {
        binder.isVolumeOffsetAvailable(device, source)
        verify(service).isVolumeOffsetAvailable(device)
    }

    @Test
    fun getNumberOfVolumeOffsetInstances() {
        binder.getNumberOfVolumeOffsetInstances(device, source)
        verify(service).getNumberOfVolumeOffsetInstances(device)
    }

    @Test
    fun setVolumeOffset() {
        val instanceId = 1
        val volumeOffset = 2

        binder.setVolumeOffset(device, instanceId, volumeOffset, source)
        verify(service).setVolumeOffset(device, instanceId, volumeOffset)
    }

    @Test
    fun setDeviceVolume() {
        val volume = 1
        val isGroupOp = true

        binder.setDeviceVolume(device, volume, isGroupOp, source)
        verify(service).setDeviceVolume(device, volume, isGroupOp)
    }

    @Test
    fun getNumberOfAudioInputControlServices() {
        binder.getNumberOfAudioInputControlServices(source, device)
    }

    @Test
    fun registerAudioInputControlCallback() {
        val instanceId = 1
        val callback = mock<IAudioInputCallback>()

        binder.registerAudioInputControlCallback(source, device, instanceId, callback)
    }

    @Test
    fun unregisterAudioInputControlCallback() {
        val instanceId = 1
        val callback = mock<IAudioInputCallback>()

        binder.unregisterAudioInputControlCallback(source, device, instanceId, callback)
    }

    @Test
    fun getAudioInputGainSettingUnit() {
        val instanceId = 1
        binder.getAudioInputGainSettingUnit(source, device, instanceId)
    }

    @Test
    fun getAudioInputGainSettingMin() {
        val instanceId = 1
        binder.getAudioInputGainSettingMin(source, device, instanceId)
    }

    @Test
    fun getAudioInputGainSettingMax() {
        val instanceId = 1
        binder.getAudioInputGainSettingMax(source, device, instanceId)
    }

    @Test
    fun getAudioInputDescription() {
        val instanceId = 1
        binder.getAudioInputDescription(source, device, instanceId)
    }

    @Test
    fun isAudioInputDescriptionWritable() {
        val instanceId = 1
        binder.isAudioInputDescriptionWritable(source, device, instanceId)
    }

    @Test
    fun setAudioInputDescription() {
        val instanceId = 1
        val description = "test"
        binder.setAudioInputDescription(source, device, instanceId, description)
    }

    @Test
    fun getAudioInputStatus() {
        val instanceId = 1
        binder.getAudioInputStatus(source, device, instanceId)
    }

    @Test
    fun getAudioInputType() {
        val instanceId = 1
        binder.getAudioInputType(source, device, instanceId)
    }

    @Test
    fun getAudioInputGainSetting() {
        val instanceId = 1
        binder.getAudioInputGainSetting(source, device, instanceId)
    }

    @Test
    fun setAudioInputGainSetting() {
        val instanceId = 1
        val gainSetting = 2
        binder.setAudioInputGainSetting(source, device, instanceId, gainSetting)
    }

    @Test
    fun getAudioInputGainMode() {
        val instanceId = 1
        binder.getAudioInputGainMode(source, device, instanceId)
    }

    @Test
    fun setAudioInputGainMode() {
        val instanceId = 1
        val gainMode = 2
        binder.setAudioInputGainMode(source, device, instanceId, gainMode)
    }

    @Test
    fun getAudioInputMute() {
        val instanceId = 1
        binder.getAudioInputMute(source, device, instanceId)
    }

    @Test
    fun setAudioInputMute() {
        val instanceId = 1
        val mute = 2
        binder.setAudioInputMute(source, device, instanceId, mute)
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
