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

package com.android.bluetooth.hfp

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

/** Test cases for [HeadsetServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HeadsetServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HeadsetService

    private val device = getTestDevice(39)

    private lateinit var binder: HeadsetServiceBinder

    @Before
    fun setUp() {
        binder = HeadsetServiceBinder(service)
    }

    @Test
    fun connect() {
        binder.connect(device, source)
        verify(service).connect(device)
    }

    @Test
    fun disconnect() {
        binder.disconnect(device, source)
        verify(service).disconnect(device)
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
    fun isNoiseReductionSupported() {
        binder.isNoiseReductionSupported(device, source)
        verify(service).isNoiseReductionSupported(device)
    }

    @Test
    fun isVoiceRecognitionSupported() {
        binder.isVoiceRecognitionSupported(device, source)
        verify(service).isVoiceRecognitionSupported(device)
    }

    @Test
    fun startVoiceRecognition() {
        binder.startVoiceRecognition(device, source)
        verify(service).startVoiceRecognition(device)
    }

    @Test
    fun stopVoiceRecognition() {
        binder.stopVoiceRecognition(device, source)
        verify(service).stopVoiceRecognition(device)
    }

    @Test
    fun isAudioConnected() {
        binder.isAudioConnected(device, source)
        verify(service).isAudioConnected(device)
    }

    @Test
    fun getAudioState() {
        binder.getAudioState(device, source)
        verify(service).getAudioState(device)
    }

    @Test
    fun connectAudio() {
        binder.connectAudio(source)
        verify(service).connectAudio()
    }

    @Test
    fun disconnectAudio() {
        binder.disconnectAudio(source)
        verify(service).disconnectAudio()
    }

    @Test
    fun setAudioRouteAllowed() {
        val allowed = true
        binder.setAudioRouteAllowed(allowed, source)
        verify(service).setAudioRouteAllowed(allowed)
    }

    @Test
    fun getAudioRouteAllowed() {
        binder.getAudioRouteAllowed(source)
        verify(service).audioRouteAllowed
    }

    @Test
    fun startScoUsingVirtualVoiceCall() {
        binder.startScoUsingVirtualVoiceCall(source)
        verify(service).startScoUsingVirtualVoiceCall()
    }

    @Test
    fun stopScoUsingVirtualVoiceCall() {
        binder.stopScoUsingVirtualVoiceCall(source)
        verify(service).stopScoUsingVirtualVoiceCall()
    }
}
