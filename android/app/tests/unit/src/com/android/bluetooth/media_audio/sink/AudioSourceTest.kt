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

package com.android.bluetooth.media_audio.sink

import android.bluetooth.BluetoothDevice
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class AudioSourceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var callback: AudioSource.Callback

    private val device = getTestDevice(34)

    private val source = TestAudioSource(device, AudioSource.Protocol.A2DP_SINK)

    private var lastStreamCommand = StreamCommand.UNKNOWN
    private var activeState: Boolean = false
    private var focusState: Int = AudioManager.AUDIOFOCUS_NONE

    @Before
    fun setUp() {
        lastStreamCommand = StreamCommand.UNKNOWN
    }

    // Callback management

    /* Test: AudioSource should drop its callback reference, preventing the flow of events */
    @Test
    fun unregisterCallback_stopsReceivingEvents() {
        source.registerCallback(callback)

        source.setStreamState(AudioSource.StreamState.STREAMING)
        verify(callback).onStreamStateChanged(AudioSource.StreamState.STREAMING)
        clearInvocations(callback)

        source.unregisterCallback()

        source.setStreamState(AudioSource.StreamState.OPEN)
        verify(callback, never()).onStreamStateChanged(any())
    }

    /* Test: AudioSource should gracefully handle unregister callback calls when there was nothing
     * previously registered
     */
    @Test
    fun unregisterCallback_withoutCallbackRegistered_noOp() {
        source.unregisterCallback()

        source.setStreamState(AudioSource.StreamState.OPEN)
        verify(callback, never()).onStreamStateChanged(any())
    }

    // get/setStreamState

    /* Test: AudioSource implementers can set their stream state. A registered callback will
     * be updated of the new value and our internal state will update as well
     */
    @Test
    fun setStreamState_toValue_getStreamStateReturnsNullAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setStreamState(AudioSource.StreamState.STREAMING)
        verify(callback).onStreamStateChanged(eq(AudioSource.StreamState.STREAMING))
        assertThat(source.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)
    }

    /* Test: AudioSource implementers can set their stream state even when a callback isn't
     * registered. This will update the internal state only
     */
    @Test
    fun setStreamState_toValueWithNoCallbackRegistered_noCallbackInvoked() {
        source.setStreamState(AudioSource.StreamState.STREAMING)
        verify(callback, never()).onStreamStateChanged(any())
        assertThat(source.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)
    }

    /* Test: The default value of stream state is "UNKNOWN" */
    @Test
    fun setStreamState_noneSet_returnsUnknown() {
        assertThat(source.getStreamState()).isEqualTo(AudioSource.StreamState.UNKNOWN)
    }

    // Stream commands

    /* Test: A prepare() command should be passed through to the implementation */
    @Test
    fun prepare_streamCommandIssued() {
        source.prepare()
        assertThat(lastStreamCommand).isEqualTo(StreamCommand.PREPARE)
    }

    /* Test: A start() command should be passed through to the implementation */
    @Test
    fun start_streamCommandIssued() {
        source.start()
        assertThat(lastStreamCommand).isEqualTo(StreamCommand.START)
    }

    /* Test: A suspend() command should be passed through to the implementation */
    @Test
    fun suspend_streamCommandIssued() {
        source.suspend()
        assertThat(lastStreamCommand).isEqualTo(StreamCommand.SUSPEND)
    }

    /* Test: A release() command should be passed through to the implementation */
    @Test
    fun release_streamCommandIssued() {
        source.release()
        assertThat(lastStreamCommand).isEqualTo(StreamCommand.RELEASE)
    }

    // toString

    /* Test: toString should provide some content by default and should not crash */
    @Test
    fun toString_doesNotCrash() {
        assertThat(source.toString()).isNotEmpty()
    }

    /* Test: dump should provide some content by default and should not crash */
    @Test
    fun dump_doesNotCrash() {
        assertThat(source.dump()).isNotEmpty()
    }

    // AudioSource is meant to be extended by a given protocol, like A2DP Sink or BAP. To test it
    // fully, including making sure the framework in the base class routes events properly, we need
    // to extend the class ourselves to intercept values passed to us.

    private inner class TestAudioSource(device: BluetoothDevice, protocol: AudioSource.Protocol) :
        AudioSource(device, protocol) {
        override fun prepare() {
            lastStreamCommand = StreamCommand.PREPARE
        }

        override fun start() {
            lastStreamCommand = StreamCommand.START
        }

        override fun suspend() {
            lastStreamCommand = StreamCommand.SUSPEND
        }

        override fun release() {
            lastStreamCommand = StreamCommand.RELEASE
        }
    }

    enum class StreamCommand {
        UNKNOWN,
        PREPARE,
        START,
        SUSPEND,
        RELEASE,
    }
}
