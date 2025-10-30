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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class MediaSourceTest {
    @get:Rule val mockitoRule = MockitoRule()

    private enum class PlaybackCommand {
        COMMAND_NONE,
        COMMAND_PREPARE,
        COMMAND_PLAY,
        COMMAND_PAUSE,
        COMMAND_SKIP_TO_NEXT,
        COMMAND_SKIP_TO_PREVIOUS,
        COMMAND_SKIP_TO_QUEUE_ITEM,
        COMMAND_STOP,
        COMMAND_REWIND,
        COMMAND_FAST_FORWARD,
        COMMAND_PLAY_FROM_MEDIA_ID,
        COMMAND_SET_REPEAT_MODE,
        COMMAND_SET_SHUFFLE_MODE,
    }

    @Mock private lateinit var callback: MediaSource.Callback
    @Mock private lateinit var metadata: MediaSource.Metadata
    @Mock private lateinit var playbackStatus: MediaSource.PlaybackStatus
    @Mock private lateinit var nowPlayingList: List<MediaSource.Metadata>

    private val device = getTestDevice(34)

    private val source = TestMediaSource(device, MediaSource.Protocol.AVRCP_CONTROLLER)

    private var lastPlaybackCommand = PlaybackCommand.COMMAND_NONE
    private var lastPlaybackCommandParam: String? = null

    private val TEST_ROOT_ID = "test_root_id"
    private val TEST_MEDIA_ID = "TEST_MEDIA_ID"

    // Callback management

    /* Test: MediaSource should drop its callback reference, preventing the flow of events */
    @Test
    fun unregisterCallback_stopsReceivingEvents() {
        source.registerCallback(callback)

        source.setMetadata(metadata)
        verify(callback).onMetadataChanged(eq(metadata))
        clearInvocations(callback)

        source.unregisterCallback()

        source.setMetadata(metadata)
        verify(callback, never()).onMetadataChanged(anyOrNull())
    }

    /* Test: MediaSource should gracefully handle unregister callback calls when there was nothing
     * previously registered
     */
    @Test
    fun unregisterCallback_withoutCallbackRegistered_noOp() {
        source.unregisterCallback()

        source.setMetadata(metadata)
        verify(callback, never()).onMetadataChanged(anyOrNull())
    }

    // get/setMetadata

    /* Test: MediaSource implementers updating their metadata value should cause a registered
     * callback to get the update and update our internal state
     */
    @Test
    fun setMetadata_toValue_getMetadataReturnsValueAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setMetadata(metadata)
        verify(callback).onMetadataChanged(eq(metadata))
        assertThat(source.getMetadata()).isEqualTo(metadata)
    }

    /* Test: MediaSource implementers updating their metadata value when no callback is registered
     * should update the internal state and have no issues handling the lack of a callback
     */
    @Test
    fun setMetadata_toValueWithNoCallbackRegistered_noCallbackInvoked() {
        source.setMetadata(metadata)
        verify(callback, never()).onMetadataChanged(anyOrNull())
        assertThat(source.getMetadata()).isEqualTo(metadata)
    }

    /* Test: MediaSource implementers updating their metadata value to null is okay */
    @Test
    fun setMetadata_toValueToNull_getMetadataReturnsNullAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setMetadata(metadata)
        verify(callback).onMetadataChanged(eq(metadata))
        assertThat(source.getMetadata()).isEqualTo(metadata)

        source.setMetadata(null)
        verify(callback).onMetadataChanged(null)
        assertThat(source.getMetadata()).isNull()
    }

    /* Test: The default value for metadata is null */
    @Test
    fun getMetadata_noneSet_returnsNull() {
        assertThat(source.getMetadata()).isNull()
    }

    // get/setPlaybackStatus

    /* Test: MediaSource implementers updating their playback state should cause a registered
     * callback to receive the update and update our internal state
     */
    @Test
    fun setPlaybackStatus_toValue_getPlaybackStatusReturnsValueAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setPlaybackStatus(playbackStatus)
        verify(callback).onPlaybackStatusChanged(eq(playbackStatus))
        assertThat(source.getPlaybackStatus()).isEqualTo(playbackStatus)
    }

    /* Test: MediaSource implementers updating their playback state without a registered callback
     * should update the internal state and have no issue with a missing callback
     */
    @Test
    fun setPlaybackStatus_toValueWithNoCallbackRegistered_noCallbackInvoked() {
        source.setPlaybackStatus(playbackStatus)
        verify(callback, never()).onPlaybackStatusChanged(anyOrNull())
        assertThat(source.getPlaybackStatus()).isEqualTo(playbackStatus)
    }

    /* Test: MediaSource implementers updating their playback state value to null is okay */
    @Test
    fun setPlaybackStatus_toValueToNull_getPlaybackStatusReturnsNullAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setPlaybackStatus(playbackStatus)
        verify(callback).onPlaybackStatusChanged(eq(playbackStatus))
        assertThat(source.getPlaybackStatus()).isEqualTo(playbackStatus)

        source.setPlaybackStatus(null)
        verify(callback).onPlaybackStatusChanged(null)
        assertThat(source.getPlaybackStatus()).isNull()
    }

    /* Test: The default value for playback state is null */
    @Test
    fun getPlaybackStatus_noneSet_returnsNull() {
        assertThat(source.getPlaybackStatus()).isNull()
    }

    // get/setNowPlayingList

    /* Test: MediaSource implementers updating their now playing queue should cause a registered
     * callback to receive the update and update our internal state
     */
    @Test
    fun setNowPlayingQueue_toValue_getPlaybackStatusReturnsNullAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setNowPlayingList(nowPlayingList)
        verify(callback).onNowPlayingListChanged(eq(nowPlayingList))
        assertThat(source.getNowPlayingList()).isEqualTo(nowPlayingList)
    }

    /* Test: MediaSource implementers updating their now playing queue state without a registered
     * callback should update the internal state and have no issue with a missing callback
     */
    @Test
    fun setNowPlayingQueue_toValueWithNoCallbackRegistered_noCallbackInvoked() {
        source.setNowPlayingList(nowPlayingList)
        verify(callback, never()).onNowPlayingListChanged(anyOrNull())
        assertThat(source.getNowPlayingList()).isEqualTo(nowPlayingList)
    }

    /* Test: MediaSource implementers can update the queue to null to indicate its gone or empty,
     * but our implementation will gracefully handle null and replace it with an empty list
     */
    @Test
    fun setNowPlayingQueue_toValueToNull_getNowPlayingQueueReturnsEmptyAndCallbackInvoked() {
        source.registerCallback(callback)

        source.setNowPlayingList(nowPlayingList)
        verify(callback).onNowPlayingListChanged(eq(nowPlayingList))
        assertThat(source.getNowPlayingList()).isEqualTo(nowPlayingList)

        source.setNowPlayingList(null)
        verify(callback).onNowPlayingListChanged(eq(emptyList()))
        assertThat(source.getNowPlayingList()).isNotNull()
        assertThat(source.getNowPlayingList()).isEmpty()
    }

    /* Test: The default value for a now playing list is an empty list (not null) */
    @Test
    fun getNowPlayingQueue_noneSet_returnsNull() {
        assertThat(source.getNowPlayingList()).isNotNull()
        assertThat(source.getNowPlayingList()).isEmpty()
    }

    // Playback commands

    /* Test: Incoming Prepare operations should be passed to the implementation */
    @Test
    fun onPrepare_commandPassedThrough() {
        source.onPrepare()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_PREPARE)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming Play operations should be passed to the implementation */
    @Test
    fun onPlay_commandPassedThrough() {
        source.onPlay()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_PLAY)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming Pause operations should be passed to the implementation */
    @Test
    fun onPause_commandPassedThrough() {
        source.onPause()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_PAUSE)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming SkipToNext operations should be passed to the implementation */
    @Test
    fun onSkipToNext_commandPassedThrough() {
        source.onSkipToNext()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_SKIP_TO_NEXT)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming SkipToPrevious operations should be passed to the implementation */
    @Test
    fun onSkipToPrevious_commandPassedThrough() {
        source.onSkipToPrevious()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_SKIP_TO_PREVIOUS)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming SkipToQueueItem operations should be passed to the implementation */
    @Test
    fun onSkipToQueueItem_commandPassedThrough() {
        source.onSkipToQueueItem(0)
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_SKIP_TO_QUEUE_ITEM)
        assertThat(lastPlaybackCommandParam).isEqualTo("0")
    }

    /* Test: Incoming Stop operations should be passed to the implementation */
    @Test
    fun onStop_commandPassedThrough() {
        source.onStop()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_STOP)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming Rewind operations should be passed to the implementation */
    @Test
    fun onRewind_commandPassedThrough() {
        source.onRewind()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_REWIND)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming FastForward operations should be passed to the implementation */
    @Test
    fun onFastForward_commandPassedThrough() {
        source.onFastForward()
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_FAST_FORWARD)
        assertThat(lastPlaybackCommandParam).isNull()
    }

    /* Test: Incoming PlayFromMediaId operations should be passed to the implementation */
    @Test
    fun onPlayFromMediaId_commandPassedThrough() {
        source.onPlayFromMediaId("0")
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_PLAY_FROM_MEDIA_ID)
        assertThat(lastPlaybackCommandParam).isEqualTo("0")
    }

    /* Test: Incoming SetRepeatMode operations should be passed to the implementation */
    @Test
    fun onSetRepeatMode_commandPassedThrough() {
        source.onSetRepeatMode(MediaSource.RepeatMode.OFF)
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_SET_REPEAT_MODE)
        assertThat(lastPlaybackCommandParam).isEqualTo("OFF")
    }

    /* Test: Incoming SetShuffleMode operations should be passed to the implementation */
    @Test
    fun onSetShuffleMode_commandPassedThrough() {
        source.onSetShuffleMode(MediaSource.ShuffleMode.OFF)
        assertThat(lastPlaybackCommand).isEqualTo(PlaybackCommand.COMMAND_SET_SHUFFLE_MODE)
        assertThat(lastPlaybackCommandParam).isEqualTo("OFF")
    }

    // Browsing commands

    /* Test: MediaSources should return a root BrowseNode when asked */
    @Test
    fun getRoot_returnsRootMediaId() {
        val root = source.getRoot()
        assertThat(root).isNotNull()
        assertThat(root?.mediaId).isEqualTo(TEST_ROOT_ID)
    }

    /* Test: By default, MediaSources do no support browsing, but requests are still passed through
     * for possible processing
     */
    @Test
    fun browse_validMediaId_returnsBrowseNode() {
        val expected =
            MediaSource.BrowseResult(null, MediaSource.BrowseStatus.ERROR_BROWSE_NOT_SUPPORTED)
        val result = source.browse(MediaSource.BrowseRequest(TEST_MEDIA_ID))

        assertThat(result.status).isEqualTo(expected.status)
        assertThat(result.results).isEqualTo(expected.results)
    }

    /* Test: MediaSources can indicate changes to BrowseNode content or status by invoking the
     * onBrowseNodeChanged() function. This should pass on to the registered callback
     */
    @Test
    fun onBrowseNodeChanged_callbackInvoked() {
        source.registerCallback(callback)

        source.onBrowseNodeChanged(TEST_MEDIA_ID)
        verify(callback).onBrowseNodeChanged(eq(TEST_MEDIA_ID))
    }

    /* Test: MediaSource calls to onBrowseNodeChanged without a callback registered should be
     * handled gracefully
     */
    @Test
    fun onBrowseNodeChanged_noCallbackRegistered_noCallbackInvoked() {
        source.onBrowseNodeChanged(TEST_MEDIA_ID)
        verify(callback, never()).onBrowseNodeChanged(anyString())
    }

    // toString

    /* Test: toString should provide some content by default and should not crash */
    @Test
    fun toString_doesNotCrash() {
        assertThat(source.toString()).isNotEmpty()
    }

    /* Test: dump should provide some content by default and should not crash  */
    @Test
    fun dump_doesNotCrash() {
        assertThat(source.dump()).isNotEmpty()
    }

    // MediaSource is meant to be extended by a given protocol, like AVRCP Controller or MCP Client.
    // To test it fully, including making sure the framework in the base class routes events
    // properly, we need to extend the class ourselves to intercept values passed to us.

    private inner class TestMediaSource(device: BluetoothDevice, protocol: MediaSource.Protocol) :
        MediaSource(device, protocol) {
        override fun onPrepare() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_PREPARE
            lastPlaybackCommandParam = null
        }

        override fun onPlay() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_PLAY
            lastPlaybackCommandParam = null
        }

        override fun onPause() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_PAUSE
            lastPlaybackCommandParam = null
        }

        override fun onSkipToNext() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_SKIP_TO_NEXT
            lastPlaybackCommandParam = null
        }

        override fun onSkipToPrevious() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_SKIP_TO_PREVIOUS
            lastPlaybackCommandParam = null
        }

        override fun onSkipToQueueItem(id: Long) {
            lastPlaybackCommand = PlaybackCommand.COMMAND_SKIP_TO_QUEUE_ITEM
            lastPlaybackCommandParam = id.toString()
        }

        override fun onStop() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_STOP
            lastPlaybackCommandParam = null
        }

        override fun onRewind() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_REWIND
            lastPlaybackCommandParam = null
        }

        override fun onFastForward() {
            lastPlaybackCommand = PlaybackCommand.COMMAND_FAST_FORWARD
            lastPlaybackCommandParam = null
        }

        override fun onPlayFromMediaId(id: String) {
            lastPlaybackCommand = PlaybackCommand.COMMAND_PLAY_FROM_MEDIA_ID
            lastPlaybackCommandParam = id
        }

        override fun onSetRepeatMode(mode: MediaSource.RepeatMode) {
            lastPlaybackCommand = PlaybackCommand.COMMAND_SET_REPEAT_MODE
            lastPlaybackCommandParam = mode.toString()
        }

        override fun onSetShuffleMode(mode: MediaSource.ShuffleMode) {
            lastPlaybackCommand = PlaybackCommand.COMMAND_SET_SHUFFLE_MODE
            lastPlaybackCommandParam = mode.toString()
        }

        override fun onGetRoot(): BrowseNode? {
            return BrowseNode(TEST_ROOT_ID, null, false, true)
        }

        override fun onBrowseRequest(request: BrowseRequest): BrowseResult {
            return BrowseResult(null, BrowseStatus.ERROR_BROWSE_NOT_SUPPORTED)
        }
    }
}
