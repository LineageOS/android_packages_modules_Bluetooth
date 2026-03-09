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
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever

private const val CONTEXT_TYPE_CONVERSATIONAL = 0x0002
private const val CONTEXT_TYPE_MEDIA = 0x0004

@EnableFlags(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
@SmallTest
@RunWith(MockitoJUnitRunner::class)
class PeripheralPolicyManagerTest {

    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var service: LeAudioPeripheralService
    @Mock private lateinit var nativeInterface: LeAudioPeripheralNativeInterface
    @Mock private lateinit var adapterService: AdapterService

    private lateinit var policyManager: PeripheralPolicyManager
    private lateinit var testLooper: TestLooper
    private lateinit var testDevice1: BluetoothDevice
    private lateinit var testDevice2: BluetoothDevice

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        testLooper = TestLooper()
        policyManager =
            PeripheralPolicyManager(service, nativeInterface, Handler(testLooper.looper))

        // Mock the side effect of service calls to update the policy manager's state
        doAnswer { invocation ->
                val device = invocation.getArgument(0, BluetoothDevice::class.java)
                policyManager.activeSinkDevice = device
                null // doAnswer requires a return value
            }
            .whenever(service)
            .updateActiveSinkDevice(any(), any())

        doAnswer { invocation ->
                val device = invocation.getArgument(0, BluetoothDevice::class.java)
                policyManager.activeSourceDevice = device
                null // doAnswer requires a return value
            }
            .whenever(service)
            .updateActiveSourceDevice(any(), any())

        // Get a test device
        val adapter = BluetoothAdapter.getDefaultAdapter()
        testDevice1 = adapter.getRemoteDevice("00:01:02:03:04:05")
        testDevice2 = adapter.getRemoteDevice("06:07:08:09:0A:0B")
    }

    @After
    fun tearDown() {
        // No-op
    }

    @Test
    fun testCreation() {
        Assert.assertNotNull(policyManager)
    }

    @Test
    fun testSinkStreamRequest_firstDevice_isApproved() {
        val requests =
            listOf(
                StreamStartRequestInfo(
                    streamId = 1,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_MEDIA,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 16000,
                )
            )

        // Pre-condition: No active device
        Assert.assertNull(policyManager.activeSinkDevice)

        // Action: Request a stream
        policyManager.onStreamEnableRequest(testDevice1, requests)
        testLooper.dispatchAll()

        // Verification
        verify(nativeInterface).confirmStreamStartRequest(testDevice1, true)
        verify(service).updateActiveSinkDevice(testDevice1, null)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)
    }

    @Test
    fun testStreamRequest_lowerPriority_isRejected() {
        // Setup: testDevice1 has an active high-priority call
        val highPriorityRequests =
            listOf(
                StreamStartRequestInfo(
                    streamId = 1,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_CONVERSATIONAL,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 16000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice1, highPriorityRequests)
        policyManager.onStreamStarted(testDevice1, 1, CONTEXT_TYPE_CONVERSATIONAL)
        testLooper.dispatchAll()
        verify(service).updateActiveSinkDevice(testDevice1, null)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)

        // Action: testDevice2 requests a lower-priority media stream
        val lowPriorityRequests =
            listOf(
                StreamStartRequestInfo(
                    streamId = 1,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_MEDIA,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 48000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice2, lowPriorityRequests)
        testLooper.dispatchAll()

        // Verification: Request from testDevice2 is rejected
        verify(nativeInterface).confirmStreamStartRequest(testDevice2, false)
        verify(service, never()).updateActiveSinkDevice(testDevice2, testDevice1)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)
    }

    @Test
    fun testStreamRequest_higherPriority_preemptsActiveStream() {
        // Setup: testDevice1 has an active low-priority media stream
        val lowPriorityRequests =
            listOf(
                StreamStartRequestInfo(
                    streamId = 1,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_MEDIA,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 48000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice1, lowPriorityRequests)
        policyManager.onStreamStarted(testDevice1, 1, CONTEXT_TYPE_MEDIA)
        testLooper.dispatchAll()
        verify(service).updateActiveSinkDevice(testDevice1, null)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)

        // Action: testDevice2 requests a higher-priority call
        val highPriorityRequests =
            listOf(
                StreamStartRequestInfo(
                    streamId = 2,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_CONVERSATIONAL,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 16000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice2, highPriorityRequests)
        testLooper.dispatchAll()

        // Verification: The old stream is stopped, the new stream is approved,
        // and the active device is switched.
        val inOrder = inOrder(nativeInterface, service)
        inOrder.verify(nativeInterface).stopStream(testDevice1, 1)
        inOrder.verify(service).updateActiveSinkDevice(testDevice2, testDevice1)
        inOrder.verify(nativeInterface).confirmStreamStartRequest(testDevice2, true)

        Assert.assertEquals(testDevice2, policyManager.activeSinkDevice)
    }

    @Test
    fun testStreamRequest_bidirectional_updatesBothDevices() {
        // 1. Start a SINK stream
        val sinkRequest =
            listOf(
                StreamStartRequestInfo(
                    streamId = 1,
                    direction = StreamDirection.SINK,
                    audioContextType = CONTEXT_TYPE_MEDIA,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 48000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice1, sinkRequest)
        testLooper.dispatchAll()
        verify(service).updateActiveSinkDevice(testDevice1, null)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)
        Assert.assertNull(policyManager.activeSourceDevice)

        // 2. Add a SOURCE stream to the same device
        val sourceRequest =
            listOf(
                StreamStartRequestInfo(
                    streamId = 2,
                    direction = StreamDirection.SOURCE,
                    audioContextType = CONTEXT_TYPE_CONVERSATIONAL,
                    codecId = CodecId(0, 0, 0),
                    sampleRate = 16000,
                )
            )
        policyManager.onStreamEnableRequest(testDevice1, sourceRequest)
        testLooper.dispatchAll()

        // Verification: The source device is activated, sink device is unchanged
        verify(service).updateActiveSourceDevice(testDevice1, null)
        Assert.assertEquals(testDevice1, policyManager.activeSourceDevice)
        Assert.assertEquals(testDevice1, policyManager.activeSinkDevice)
    }
}
