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

package com.android.bluetooth.mcp

import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class McpClientNativeCallbackTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var service: McpClientService

    private lateinit var nativeCallback: McpClientNativeCallback
    private val device: BluetoothDevice = TestUtils.getTestDevice(0)
    private val deviceAddress: ByteArray = Util.getBytesFromAddress(device.address)

    @Before
    fun setUp() {
        doReturn(device).whenever(adapterService).getDeviceFromByte(any())

        // Stub service.post() to run immediately.
        // We use @Suppress("UNCHECKED_CAST") because generic function types are erased at runtime.
        doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val block = invocation.arguments[0] as (McpClientService) -> Unit
                block(service)
            }
            .whenever(service)
            .post(any())

        nativeCallback = McpClientNativeCallback(adapterService, service)
    }

    @Test
    fun onConnectionStateChanged() {
        val state = 2
        nativeCallback.onConnectionStateChanged(deviceAddress, state)
        verify(service).onConnectionStateChanged(device, state)
    }

    @Test
    fun onDiscovered() {
        nativeCallback.onDiscovered(deviceAddress)
        verifyStackEvent(type = McpStackEvent.EVENT_TYPE_DISCOVERED)
    }

    @Test
    fun onMediaPlayerNameChanged() {
        val id = 1
        val name = "test_player"
        nativeCallback.onMediaPlayerNameChanged(deviceAddress, id, name)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED,
            int1 = id,
            string1 = name,
        )
    }

    @Test
    fun onTrackChanged() {
        val id = 1
        nativeCallback.onTrackChanged(deviceAddress, id)
        verifyStackEvent(type = McpStackEvent.EVENT_TYPE_TRACK_CHANGED, int1 = id)
    }

    @Test
    fun onTrackTitleChanged() {
        val id = 1
        val title = "test_title"
        nativeCallback.onTrackTitleChanged(deviceAddress, id, title)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_TRACK_TITLE_CHANGED,
            int1 = id,
            string1 = title,
        )
    }

    @Test
    fun onTrackDurationChanged() {
        val id = 1
        val duration = 1000
        nativeCallback.onTrackDurationChanged(deviceAddress, id, duration)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_TRACK_DURATION_CHANGED,
            int1 = id,
            int2 = duration,
        )
    }

    @Test
    fun onTrackPositionChanged() {
        val id = 1
        val position = 500
        nativeCallback.onTrackPositionChanged(deviceAddress, id, position)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_TRACK_POSITION_CHANGED,
            int1 = id,
            int2 = position,
        )
    }

    @Test
    fun onPlaybackSpeedChanged() {
        val id = 1
        val speed: Byte = 10
        nativeCallback.onPlaybackSpeedChanged(deviceAddress, id, speed)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_PLAYBACK_SPEED_CHANGED,
            int1 = id,
            int2 = speed.toInt(),
        )
    }

    @Test
    fun onPlayingOrderChanged() {
        val id = 1
        val order = 2
        nativeCallback.onPlayingOrderChanged(deviceAddress, id, order)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_PLAYING_ORDER_CHANGED,
            int1 = id,
            int2 = order,
        )
    }

    @Test
    fun onPlayingOrdersSupportedChanged() {
        val id = 1
        val orders = 5
        nativeCallback.onPlayingOrdersSupportedChanged(deviceAddress, id, orders)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED,
            int1 = id,
            int2 = orders,
        )
    }

    @Test
    fun onSeekingSpeedChanged() {
        val id = 1
        val speed: Byte = 5
        nativeCallback.onSeekingSpeedChanged(deviceAddress, id, speed)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_SEEKING_SPEED_CHANGED,
            int1 = id,
            int2 = speed.toInt(),
        )
    }

    @Test
    fun onMediaStateChanged() {
        val id = 1
        val state = 3
        nativeCallback.onMediaStateChanged(deviceAddress, id, state)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_MEDIA_STATE_CHANGED,
            int1 = id,
            int2 = state,
        )
    }

    @Test
    fun onMediaControlResult() {
        val id = 1
        val opcode = 2
        val result = 0
        nativeCallback.onMediaControlResult(deviceAddress, id, opcode, result)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_MEDIA_CONTROL_RESULT,
            int1 = id,
            int2 = opcode,
            int3 = result,
        )
    }

    @Test
    fun onOpcodesSupportedChanged() {
        val id = 1
        val opcodes = 10
        nativeCallback.onOpcodesSupportedChanged(deviceAddress, id, opcodes)
        verifyStackEvent(
            type = McpStackEvent.EVENT_TYPE_OPCODES_SUPPORTED_CHANGED,
            int1 = id,
            int2 = opcodes,
        )
    }

    /** Consolidates verification of McpStackEvents. */
    private fun verifyStackEvent(
        type: Int,
        int1: Int = 0,
        int2: Int = 0,
        int3: Int = 0,
        string1: String? = null,
    ) {
        val captor = argumentCaptor<McpStackEvent>()
        verify(service).messageFromNative(captor.capture())
        val event = captor.firstValue

        assertThat(event.type).isEqualTo(type)
        assertThat(event.device).isEqualTo(device)
        assertThat(event.valueInt1).isEqualTo(int1)
        assertThat(event.valueInt2).isEqualTo(int2)
        assertThat(event.valueInt3).isEqualTo(int3)
        assertThat(event.valueString1).isEqualTo(string1)
    }
}
