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

import android.bluetooth.BluetoothProfile
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.media_audio.sink.MediaAudioServer
import com.android.bluetooth.media_audio.sink.MediaSource
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class McpClientStateMachineTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var service: McpClientService
    @Mock private lateinit var nativeInterface: McpClientNativeInterface
    @Mock private lateinit var mediaAudioServer: MediaAudioServer

    private lateinit var stateMachine: McpClientStateMachine
    private lateinit var looper: TestLooper
    private val device = TestUtils.getTestDevice(0)

    @Before
    fun setUp() {
        looper = TestLooper()

        // Default: Service allows connections
        doReturn(true).whenever(service).okToConnect(any())

        stateMachine =
            McpClientStateMachine(service, device, nativeInterface, mediaAudioServer, looper.looper)
        looper.dispatchAll() // Enter Initial State
    }

    @After
    fun tearDown() {
        stateMachine.doQuit()
        stateMachine.cleanup()
    }

    @Test
    fun initialState_isDisconnected() {
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun outgoingConnect_transitionsToConnecting() {
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_CONNECT)

        verify(nativeInterface).connect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTING)
    }

    @Test
    fun outgoingConnect_whenTimeOut_isDisconnected() {
        // 1. Start connecting
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_CONNECT)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTING)

        // 2. Simulate Timeout (30s)
        looper.moveTimeForward(30_001) // 30s + 1ms
        looper.dispatchAll()

        // 3. Should trigger disconnect and return to Disconnected state
        verify(nativeInterface).disconnect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun outgoingConnect_whenDisconnectRequest_isDisconnected() {
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_CONNECT)

        // User cancels connection before it completes
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)

        verify(nativeInterface).disconnect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun incomingConnect_whenOkToConnect_isConnected() {
        // Native stack reports connecting -> connected
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTING,
        )
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTING)

        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED,
        )
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTED)
    }

    @Test
    fun incomingConnect_whenNotOkToConnect_disconnects() {
        // Service forbids connection
        doReturn(false).whenever(service).okToConnect(any())

        // Native stack reports connecting
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTING,
        )

        // Should reject and disconnect
        verify(nativeInterface).disconnect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun incomingConnected_whenOkToConnect_isConnected() {
        // Native stack reports connected
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED,
        )

        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTED)
    }

    @Test
    fun incomingConnected_whenNotOkToConnect_disconnects() {
        // Service forbids connection
        doReturn(false).whenever(service).okToConnect(any())

        // Native stack reports connected
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED,
        )

        // Should reject and disconnect
        verify(nativeInterface).disconnect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun connect_whenConnected_doNothing() {
        transitionToConnected()

        // Sending Connect while already connected should be ignored
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_CONNECT)

        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTED)
        // Verify we didn't call native connect again
        verify(nativeInterface, never()).connect(device)
    }

    @Test
    fun disconnect_whenConnected_transitionsToDisconnecting() {
        transitionToConnected()

        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)

        verify(nativeInterface).disconnect(device)
        assertThat(stateMachine.getConnectionState())
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTING)
    }

    @Test
    fun remoteDisconnect_whenConnected_isDisconnected() {
        transitionToConnected()

        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_DISCONNECTED,
        )

        verify(nativeInterface, never()).disconnect(device)
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun disconnect_whenDisconnecting_doNothing() {
        transitionToConnected()
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT) // Enter Disconnecting
        clearInvocations(nativeInterface)

        // Send another disconnect
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)

        // Should not call native disconnect again
        verify(nativeInterface, never()).disconnect(device)
    }

    @Test
    fun outgoingConnect_whenDisconnecting_isDeferred() {
        // 1. Move to Disconnecting state
        transitionToConnected()
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)
        assertThat(stateMachine.getConnectionState())
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTING)

        // 2. User tries to Connect immediately (before disconnect finishes)
        // This message should be DEFERRED
        stateMachine.sendMessage(McpClientStateMachine.MESSAGE_CONNECT)
        looper.dispatchAll()

        // Ensure we haven't tried to connect yet (still disconnecting)
        verify(nativeInterface, never()).connect(device)

        // 3. Finish the Disconnection (Native callback)
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_DISCONNECTED,
        )

        // 4. Now the deferred CONNECT message should be processed automatically
        // State should transition: Disconnected -> Connecting
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTING)
        verify(nativeInterface).connect(device)
    }

    @Test
    fun timeout_whenDisconnecting_forceDisconnected() {
        transitionToConnected()
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)
        assertThat(stateMachine.getConnectionState())
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTING)

        // Simulate Timeout waiting for native disconnect callback
        looper.moveTimeForward(30_001)
        looper.dispatchAll()

        // Should be forced to Disconnected
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun dump_doesNotCrash() {
        transitionToConnected()
        stateMachine.dump(StringBuilder())
    }

    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    @Test
    fun transitionToConnected_registersMediaSource() {
        transitionToConnected()

        verify(mediaAudioServer).registerMediaSource(any())
    }

    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    @Test
    fun transitionToDisconnected_unregistersMediaSource() {
        transitionToConnected()

        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_DISCONNECT)
        // Move to Disconnecting...
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_DISCONNECTED,
        )

        verify(mediaAudioServer).unregisterMediaSource(any())
    }

    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    @Test
    fun mediaSource_callbacks_triggerNativeInterface() {
        transitionToConnected()

        val captor = argumentCaptor<MediaSource>()
        verify(mediaAudioServer).registerMediaSource(captor.capture())
        val registeredSource = captor.firstValue

        registeredSource.onPlay()
        verify(nativeInterface).play(device, GMCS_ID)

        registeredSource.onPause()
        verify(nativeInterface).pause(device, GMCS_ID)

        registeredSource.onSkipToNext()
        verify(nativeInterface).nextTrack(device, GMCS_ID)

        registeredSource.onSkipToPrevious()
        verify(nativeInterface).previousTrack(device, GMCS_ID)

        registeredSource.onFastForward()
        verify(nativeInterface).fastForward(device, GMCS_ID)

        registeredSource.onRewind()
        verify(nativeInterface).fastRewind(device, GMCS_ID)

        registeredSource.onStop()
        verify(nativeInterface).stop(device, GMCS_ID)

        registeredSource.onSetRepeatMode(MediaSource.RepeatMode.ONE)
        // Default PlayingOrder is IN_ORDER_ONCE. resolvePlayingOrder(IN_ORDER_ONCE, ONE, null) ->
        // SINGLE_REPEAT
        verify(nativeInterface)
            .setPlayingOrder(eq(device), eq(GMCS_ID), eq(PlayingOrder.SINGLE_REPEAT))

        registeredSource.onSetShuffleMode(MediaSource.ShuffleMode.ALL)
        // Default IN_ORDER_ONCE. resolvePlayingOrder(IN_ORDER_ONCE, null, ALL) -> SHUFFLE_ONCE
        verify(nativeInterface)
            .setPlayingOrder(eq(device), eq(GMCS_ID), eq(PlayingOrder.SHUFFLE_ONCE))
    }

    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    @Test
    fun stackEvent_updatesMediaSource_Metadata_and_PlaybackStatus() {
        transitionToConnected()

        val captor = argumentCaptor<MediaSource>()
        verify(mediaAudioServer).registerMediaSource(captor.capture())
        val registeredSource = captor.firstValue

        // 1. Update Title
        val titleEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_TITLE_CHANGED)
        titleEvent.device = device
        titleEvent.valueInt1 = GMCS_ID
        titleEvent.valueString1 = "New Title"
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, titleEvent)
        assertThat(registeredSource.getMetadata()?.title).isEqualTo("New Title")

        // 2. Update Duration
        val durationEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_DURATION_CHANGED)
        durationEvent.device = device
        durationEvent.valueInt1 = GMCS_ID
        durationEvent.valueInt2 = 60000 // ms
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, durationEvent)
        assertThat(registeredSource.getMetadata()?.duration).isEqualTo(60000L)

        // 3. Update State (Playing)
        val stateEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_MEDIA_STATE_CHANGED)
        stateEvent.device = device
        stateEvent.valueInt1 = GMCS_ID
        stateEvent.valueInt2 = MediaState.PLAYING.value
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, stateEvent)
        assertThat(registeredSource.getPlaybackStatus()?.state)
            .isEqualTo(MediaSource.PlaybackState.PLAYING)

        // 4. Update Position
        val posEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_POSITION_CHANGED)
        posEvent.device = device
        posEvent.valueInt1 = GMCS_ID
        posEvent.valueInt2 = 5000 // ms
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, posEvent)
        assertThat(registeredSource.getPlaybackStatus()?.playbackPosition).isEqualTo(5000L)

        // 5. Update Playback Speed
        val speedEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_PLAYBACK_SPEED_CHANGED)
        speedEvent.device = device
        speedEvent.valueInt1 = GMCS_ID
        speedEvent.valueInt2 = 150 // 1.5x
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, speedEvent)
        assertThat(registeredSource.getPlaybackStatus()?.playbackSpeed).isEqualTo(1.5f)

        // 6. Update Supported Opcodes -> PlayerActions
        val opcodeEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_OPCODES_SUPPORTED_CHANGED)
        opcodeEvent.device = device
        opcodeEvent.valueInt1 = GMCS_ID
        // Enable PLAY(bit 0), PAUSE(bit 1), STOP(bit 4)
        opcodeEvent.valueInt2 =
            (1 shl McpOpcode.PLAY.supportedOpcodeBit) or
                (1 shl McpOpcode.PAUSE.supportedOpcodeBit) or
                (1 shl McpOpcode.STOP.supportedOpcodeBit)
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, opcodeEvent)
        val actions = registeredSource.getPlaybackStatus()?.availableActions
        assertThat(actions).contains(MediaSource.PlayerAction.PLAY)
        assertThat(actions).contains(MediaSource.PlayerAction.PAUSE)
        assertThat(actions).contains(MediaSource.PlayerAction.STOP)
        assertThat(actions).doesNotContain(MediaSource.PlayerAction.NEXT)

        // 7. Update Playing Order -> Shuffle/Repeat Mode
        val orderEvent = McpStackEvent(McpStackEvent.EVENT_TYPE_PLAYING_ORDER_CHANGED)
        orderEvent.device = device
        orderEvent.valueInt1 = GMCS_ID
        orderEvent.valueInt2 = PlayingOrder.SHUFFLE_REPEAT.value
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, orderEvent)
        assertThat(registeredSource.getPlaybackStatus()?.shuffleMode)
            .isEqualTo(MediaSource.ShuffleMode.ALL)
        assertThat(registeredSource.getPlaybackStatus()?.repeatMode)
            .isEqualTo(MediaSource.RepeatMode.ALL)
    }

    private fun transitionToConnected() {
        sendAndDispatchMessage(McpClientStateMachine.MESSAGE_CONNECT)
        sendAndDispatchMessage(
            McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED,
        )
        assertThat(stateMachine.getConnectionState()).isEqualTo(BluetoothProfile.STATE_CONNECTED)
        clearInvocations(nativeInterface) // Reset mocks for clean state
        // NOTE: We do NOT clear mediaAudioServer here, as registration verification depends on it
    }

    private fun sendAndDispatchMessage(what: Int) {
        stateMachine.sendMessage(what)
        looper.dispatchAll()
    }

    private fun sendAndDispatchMessage(what: Int, arg1: Int) {
        stateMachine.sendMessage(what, arg1)
        looper.dispatchAll()
    }

    private fun sendAndDispatchMessage(what: Int, obj: Any) {
        stateMachine.sendMessage(what, obj)
        looper.dispatchAll()
    }
}
