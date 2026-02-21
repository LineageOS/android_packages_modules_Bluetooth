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

package com.android.bluetooth.hfpclient

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.verify

/** Test cases for [HeadsetClientServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HeadsetClientServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HeadsetClientService

    private val device = getTestDevice(54)

    private lateinit var binder: HeadsetClientServiceBinder

    @Before
    fun setUp() {
        binder = HeadsetClientServiceBinder(service)
    }

    @Test
    fun connect_callsServiceMethod() {
        binder.connect(device, source)

        verify(service).connect(device)
    }

    @Test
    fun disconnect_callsServiceMethod() {
        binder.disconnect(device, source)

        verify(service).disconnect(device)
    }

    @Test
    fun getConnectedDevices_callsServiceMethod() {
        binder.getConnectedDevices(source)

        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates_callsServiceMethod() {
        val states = intArrayOf(STATE_CONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)

        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState_callsServiceMethod() {
        binder.getConnectionState(device, source)

        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy_callsServiceMethod() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        binder.setConnectionPolicy(device, connectionPolicy, source)

        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy_callsServiceMethod() {
        binder.getConnectionPolicy(device, source)

        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun startVoiceRecognition_callsServiceMethod() {
        binder.startVoiceRecognition(device, source)

        verify(service).startVoiceRecognition(device)
    }

    @Test
    fun stopVoiceRecognition_callsServiceMethod() {
        binder.stopVoiceRecognition(device, source)

        verify(service).stopVoiceRecognition(device)
    }

    @Test
    fun getAudioState_callsServiceMethod() {
        binder.getAudioState(device, source)

        verify(service).getAudioState(device)
    }

    @Test
    fun setAudioRouteAllowed_callsServiceMethod() {
        val allowed = true
        binder.setAudioRouteAllowed(device, allowed, source)

        verify(service).setAudioRouteAllowed(device, allowed)
    }

    @Test
    fun getAudioRouteAllowed_callsServiceMethod() {
        binder.getAudioRouteAllowed(device, source)

        verify(service).getAudioRouteAllowed(device)
    }

    @Test
    fun connectAudio_callsServiceMethod() {
        binder.connectAudio(device, source)

        verify(service).connectAudio(device)
    }

    @Test
    fun disconnectAudio_callsServiceMethod() {
        binder.disconnectAudio(device, source)

        verify(service).disconnectAudio(device)
    }

    @Test
    fun acceptCall_callsServiceMethod() {
        val flag = 2
        binder.acceptCall(device, flag, source)

        verify(service).acceptCall(device, flag)
    }

    @Test
    fun rejectCall_callsServiceMethod() {
        binder.rejectCall(device, source)

        verify(service).rejectCall(device)
    }

    @Test
    fun holdCall_callsServiceMethod() {
        binder.holdCall(device, source)

        verify(service).holdCall(device)
    }

    @Test
    fun terminateCall_callsServiceMethod() {
        binder.terminateCall(device, null, source)

        verify(service).terminateCall(device, null)
    }

    @Test
    fun explicitCallTransfer_callsServiceMethod() {
        binder.explicitCallTransfer(device, source)

        verify(service).explicitCallTransfer(device)
    }

    @Test
    fun enterPrivateMode_callsServiceMethod() {
        val index = 1
        binder.enterPrivateMode(device, index, source)

        verify(service).enterPrivateMode(device, index)
    }

    @Test
    fun dial_callsServiceMethod() {
        val number = "12532523"
        binder.dial(device, number, source)

        verify(service).dial(device, number)
    }

    @Test
    fun sendDTMF_callsServiceMethod() {
        val code: Byte = 21
        binder.sendDTMF(device, code, source)

        verify(service).sendDTMF(device, code)
    }

    @Test
    fun getCurrentAgEvents_callsServiceMethod() {
        binder.getCurrentAgEvents(device, source)

        verify(service).getCurrentAgEvents(device)
    }

    @Test
    fun sendVendorAtCommand_callsServiceMethod() {
        val vendorId = 5
        val cmd = "test_command"

        binder.sendVendorAtCommand(device, vendorId, cmd, source)

        verify(service).sendVendorAtCommand(device, vendorId, cmd)
    }

    @Test
    fun getCurrentAgFeatures_callsServiceMethod() {
        binder.getCurrentAgFeatures(device, source)

        verify(service).getCurrentAgFeaturesBundle(device)
    }

    @Test
    fun cleanUp_doesNotCrash() {
        binder.cleanup()
    }
}
