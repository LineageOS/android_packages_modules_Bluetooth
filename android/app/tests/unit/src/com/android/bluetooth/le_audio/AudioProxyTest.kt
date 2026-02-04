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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.HwAudioSource
import android.os.Handler
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@EnableFlags(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
@SmallTest
@RunWith(MockitoJUnitRunner::class)
class AudioProxyTest {

    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var context: Context
    @Mock private lateinit var audioManager: AudioManager
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var policyManager: PeripheralPolicyManager
    @Mock private lateinit var mockPlaybackDeviceInfo: AudioDeviceInfo
    @Mock private lateinit var mockRecordingDeviceInfo: AudioDeviceInfo
    @Mock private lateinit var mockHwAudioSource: HwAudioSource
    @Mock private lateinit var mockBuiltInMicDeviceInfo: AudioDeviceInfo

    @Captor private lateinit var deviceCallbackCaptor: ArgumentCaptor<AudioDeviceCallback>

    private lateinit var audioProxy: AudioProxyTestable
    private lateinit var testLooper: TestLooper
    private lateinit var testDevice: BluetoothDevice

    // Test subclass to allow injection of mocks
    inner class AudioProxyTestable(
        context: Context,
        handler: Handler,
        adapterService: AdapterService,
        policyManager: PeripheralPolicyManager,
    ) : AudioProxy(context, handler, adapterService, policyManager) {
        override fun createHwAudioSource(
            deviceInfo: AudioDeviceInfo,
            isPlayback: Boolean,
        ): HwAudioSource {
            return mockHwAudioSource
        }
    }

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audioManager)
        testLooper = TestLooper()
        audioProxy =
            AudioProxyTestable(context, Handler(testLooper.looper), adapterService, policyManager)
        audioProxy.init()

        // Capture the callback to simulate device additions
        verify(audioManager)
            .registerAudioDeviceCallback(deviceCallbackCaptor.capture(), (any(Handler::class.java)))

        // Setup test device
        testDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:01:02:03:04:05")
        `when`(adapterService.getDeviceFromByte(any())).thenReturn(testDevice)

        // Setup mock audio devices
        `when`(mockPlaybackDeviceInfo.type).thenReturn(AudioDeviceInfo.TYPE_BLE_HEADSET)
        `when`(mockPlaybackDeviceInfo.address).thenReturn(testDevice.address)
        `when`(mockPlaybackDeviceInfo.isSource).thenReturn(true)
        `when`(mockPlaybackDeviceInfo.isSink).thenReturn(false)

        `when`(mockRecordingDeviceInfo.type).thenReturn(AudioDeviceInfo.TYPE_BLE_HEADSET)
        `when`(mockRecordingDeviceInfo.address).thenReturn(testDevice.address)
        `when`(mockRecordingDeviceInfo.isSource).thenReturn(false)
        `when`(mockRecordingDeviceInfo.isSink).thenReturn(true)

        // Stub the fallback path for microphone discovery
        `when`(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            .thenReturn(arrayOf(mockBuiltInMicDeviceInfo))
        `when`(mockBuiltInMicDeviceInfo.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
    }

    @After
    fun tearDown() {
        audioProxy.cleanup()
    }

    @Test
    fun testCreation() {
        Assert.assertNotNull(audioProxy)
        verify(audioManager)
            .registerAudioDeviceCallback(
                any(AudioDeviceCallback::class.java),
                any(Handler::class.java),
            )
    }

    @Test
    fun testOnDeviceAdded_forPlayback_createsHwAudioSource() {
        `when`(policyManager.activeSinkDevice).thenReturn(testDevice)
        deviceCallbackCaptor.value.onAudioDevicesAdded(arrayOf(mockPlaybackDeviceInfo))
        testLooper.dispatchAll()

        // Verify player is created but not started (rendezvous not complete)
        verify(mockHwAudioSource, never()).start()
    }

    @Test
    fun testOnDeviceAdded_forRecording_createsHwAudioSource() {
        `when`(policyManager.activeSourceDevice).thenReturn(testDevice)
        deviceCallbackCaptor.value.onAudioDevicesAdded(arrayOf(mockRecordingDeviceInfo))
        testLooper.dispatchAll()

        // Verify player is created but not started (rendezvous not complete)
        verify(mockHwAudioSource, never()).start()
    }

    @Test
    fun testRendezvous_Playback_StreamReadyFirst() {
        `when`(policyManager.activeSinkDevice).thenReturn(testDevice)

        // Action 1: Stream becomes ready
        audioProxy.onSinkStreamReady(testDevice)
        testLooper.dispatchAll()
        verify(mockHwAudioSource, never()).start()

        // Action 2: Audio device is added
        deviceCallbackCaptor.value.onAudioDevicesAdded(arrayOf(mockPlaybackDeviceInfo))
        testLooper.dispatchAll()

        // Verification: Player is now started
        verify(mockHwAudioSource).start()
    }

    @Test
    fun testRendezvous_Recording_StreamReadyFirst() {
        `when`(policyManager.activeSourceDevice).thenReturn(testDevice)

        // Action 1: Stream becomes ready
        audioProxy.onSourceStreamReady(testDevice)
        testLooper.dispatchAll()
        verify(mockHwAudioSource, never()).start()

        // Action 2: Audio device is added
        deviceCallbackCaptor.value.onAudioDevicesAdded(arrayOf(mockRecordingDeviceInfo))
        testLooper.dispatchAll()

        // Verification: Recorder is now started
        verify(mockHwAudioSource).start()
    }
}
