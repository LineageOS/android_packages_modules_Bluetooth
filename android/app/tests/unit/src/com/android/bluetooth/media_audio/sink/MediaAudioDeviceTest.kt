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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.tests.bluetooth.staticMockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class MediaAudioDeviceTest {
    @get:Rule val sMockitoRule = staticMockitoRule<Executors>()

    @Mock private lateinit var deviceCallback: MediaAudioDevice.Callback

    private val executor = TestExecutor()

    private lateinit var mediaSourceClassic: MediaSource
    private lateinit var mediaSourceLe: MediaSource
    private lateinit var audioSourceClassic: AudioSource
    private lateinit var audioSourceLe: AudioSource

    private val device = getTestDevice(34)

    private lateinit var mediaAudioDevice: MediaAudioDevice

    @Before
    fun setUp() {
        ExtendedMockito.doReturn(executor).`when` { Executors.newSingleThreadExecutor() }
        executor.startAutoDispatch()

        mediaAudioDevice = MediaAudioDevice(device, deviceCallback)

        mediaSourceClassic = spy(MediaSource(device, MediaSource.Protocol.AVRCP_CONTROLLER))
        audioSourceClassic = spy(AudioSource(device, AudioSource.Protocol.A2DP_SINK))

        mediaSourceLe = spy(MediaSource(device, MediaSource.Protocol.MCP_CLIENT))
        audioSourceLe = spy(AudioSource(device, AudioSource.Protocol.BAP_UNICAST_SERVER))
    }

    // Source Management

    // Add - media

    /* Test: The first source added will always be the preferred source, even if its Classic */
    @Test
    fun addMediaSource_firstSourceIsClassic_sourceAddedCallbackRegisteredSourceIsPreferred() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if its LE */
    @Test
    fun addMediaSource_firstSourceIsLe_sourceAddedCallbackRegisteredSourceIsPreferred() {
        addMediaSource(expectedCount = 1, mediaSourceLe)

        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if LE is connected
     * after classic
     */
    @Test
    fun addMediaSource_secondSourceIsLe_sourceAddedCallbackRegisteredFirstSourceIsPreferred() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if LE is connected
     * first, then Classic
     */
    @Test
    fun addMediaSource_secondSourceIsClassic_sourceAddedCallbackRegisteredFirstSourceIsPreferred() {
        addMediaSource(expectedCount = 1, mediaSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    /* Test: Adding the same source/protocol twice results in a no-op for the second source */
    @Test
    fun addMediaSource_sameSourceTwice_secondAdditionNoOp() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        mediaAudioDevice.addMediaSource(mediaSourceClassic)
        assertThat(mediaAudioDevice.getMediaProtocols().size).isEqualTo(1)
        assertThat(mediaAudioDevice.getMediaProtocols()).contains(mediaSourceClassic.protocol)

        verify(mediaSourceClassic, times(1)).registerCallback(any<MediaSource.Callback>())
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    /* Test: Registering a media source while inactive will not send a pause if that source is not
     * in the playing state
     */
    @Test
    fun addMediaSource_whileInactiveSourceIsPaused_noPauseSent() {
        val status = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceClassic, status)
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        verify(mediaSourceClassic, never()).onPause()
    }

    /* Test: Registering a media source while inactive will not send a pause if that source is not
     * in the playing state
     */
    @Test
    fun addMediaSource_whileInactiveSourceIsPlaying_pauseSent() {
        val status = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, status)
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        verify(mediaSourceClassic).onPause()
    }

    /* Test: Registering a media source while active will allow playback with a pause if that source
     * becomes the preferred source.
     */
    @Test
    fun addMediaSource_whileActiveWithNoPreferredSourceSourceInPlayingState_noPauseSent() {
        mediaAudioDevice.setActiveSource(true)
        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        val status = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, status)
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        verify(mediaSourceClassic, never()).onPause()
    }

    /* Test: Registering a media source while active will pause playback with a pause if that source
     * does not become the preferred source.
     */
    @Test
    fun addMediaSource_whileActiveWithPreferredSourceSourceInPlayingState_pauseSent() {
        mediaAudioDevice.setActiveSource(true)
        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        val status = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, status)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        verify(mediaSourceLe, never()).onPause()

        setPlaybackStatusForSource(mediaSourceClassic, status)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        verify(mediaSourceClassic).onPause()
    }

    // Add - audio

    /* Test: The first source added will always be the preferred source, evne if its classic */
    @Test
    fun addAudioSource_firstSourceIsClassic_sourceAddedCallbackRegisteredSourceIsPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if its LE */
    @Test
    fun addAudioSource_firstSourceIsLe_sourceAddedCallbackRegisteredSourceIsPreferred() {
        addAudioSource(expectedCount = 1, audioSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if LE connects after
     * Classic
     */
    @Test
    fun addAudioSource_secondSourceIsLe_sourceAddedCallbackRegisteredFirstSourceIsPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addAudioSource(expectedCount = 2, audioSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: The first source added will always be the preferred source, even if LE is connected
     * first, then Classic
     */
    @Test
    fun addAudioSource_secondSourceIsClassic_sourceAddedCallbackRegisteredFirstSourceIsPreferred() {
        addAudioSource(expectedCount = 1, audioSourceLe)
        addAudioSource(expectedCount = 2, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Adding the same source/protocol twice results in a no-op for the second source */
    @Test
    fun addAudioSource_sameSourceTwice_secondAdditionNoOp() {
        addAudioSource(expectedCount = 1, audioSourceClassic)

        mediaAudioDevice.addAudioSource(audioSourceClassic)
        assertThat(mediaAudioDevice.getAudioProtocols().size).isEqualTo(1)
        assertThat(mediaAudioDevice.getAudioProtocols()).contains(audioSourceClassic.protocol)

        verify(audioSourceClassic, times(1)).registerCallback(any<AudioSource.Callback>())
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Adding a source that updates the preferred audio source while we're active will
     * cause that source to be prepared()
     */
    @Test
    fun addAudioSource_whileActiveWithNoPreferredSource_sourcePrepared() {
        mediaAudioDevice.setActiveSource(true)
        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        addAudioSource(expectedCount = 1, audioSourceClassic)

        verify(audioSourceClassic).prepare()
    }

    /* Test: Adding a source that does not update the preferred audio source while we're active will
     * not cause that source to be prepared()
     */
    @Test
    fun addAudioSource_whileActiveWithPreferredSource_sourceNotPrepared() {
        mediaAudioDevice.setActiveSource(true)
        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        addAudioSource(expectedCount = 1, audioSourceLe)

        verify(audioSourceLe).prepare()

        addAudioSource(expectedCount = 2, audioSourceClassic)

        verify(audioSourceClassic, never()).prepare()
    }

    // Add - mix and match sources in any connection order + edge cases

    /* Test: Order does not matter for media/audio preferred source values. Registering media and
     * then audio results in both being preferred
     */
    @Test
    fun addMediaSourceThenAddAudioSource_bothSourcesAddedCallbacksRegisteredSourcesPreferred() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 1, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    /* Test: Order does not matter for media/audio preferred source value. Registering audio and
     * then media results in both being preferred
     */
    @Test
    fun addAudioSourceThenAddMediaSource_bothSourcesAddedCallbacksRegisteredSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    /* Test: Source transport does not matter for media/audio preferred source values. Transports
     * can be mixed across audio and media. This proves LE Audio with Classic Media
     */
    @Test
    fun mixLeAudioAndClassicMediaSources_bothSourcesAddedCallbacksRegisteredSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceLe)
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    /* Test: Source transport does not matter for media/audio preferred source values. Transports
     * can be mixed across audio and media. This proves Classic Audio with LE Media
     */
    @Test
    fun mixClassicAudioAndLeMediaSources_bothSourcesAddedCallbacksRegisteredSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)
    }

    // 3 Sources

    /* Test: MediaAudioDevice can handle multiple sources per type. The first connected becomes
     * preferred for each type. Here, LE and Classic Media are supported with Classic Audio, but
     * LE Media connects first and becomes the preferred media source.
     */
    @Test
    fun addDualMediaAndClassicAudio_callbacksRegisteredAndFirstConnectedSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 1, mediaSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)
    }

    /* Test: MediaAudioDevice can handle multiple sources per type. The first connected becomes
     * preferred for each type. Here, LE and Classic Audio are supported with Classic Media, but
     * Classic Audio connects first and becomes the preferred audio source.
     */
    @Test
    fun addDualAudioAndClassicMedia_callbacksRegisteredAndFirstConnectedSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 2, audioSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    // 4 Sources

    /* Test: MediaAudioDevice can handle full dual mode connections, where the first sources
     * connected become preferred
     */
    @Test
    fun addFullDualModeDevice_callbacksRegisteredAndFirstConnectedSourcesPreferred() {
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 2, audioSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    // Remove - media

    /* Test: Removing the final source when it's a media source leaves an empty preferred source */
    @Test
    fun removeMediaSource_noMoreMediaSources_sourceRemovedCallbackRemovedPreferredSourceNull() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)

        removeMediaSource(0, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Removing the non-preferred source will not have an impact on the preferred source */
    @Test
    fun removeMediaSource_preferredSourceLeft_sourceRemovedCallbackRemovedPreferredSourceRemains() {
        addMediaSource(expectedCount = 1, mediaSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)

        removeMediaSource(1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)
    }

    /* Test: Removing the preferred source will cause a new preferred source to be set */
    @Test
    fun removeMediaSource_nonPreferredSourceLeft_sourceRemovedCallbackRemovedPreferredSourceSet() {
        addMediaSource(expectedCount = 1, mediaSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceLe)

        removeMediaSource(1, mediaSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isEqualTo(mediaSourceClassic)
    }

    /* Test: Calling remove on a non-existent source is a safe no-op*/
    @Test
    fun removeMediaSource_sourceDoesNotExist_noOp() {
        mediaAudioDevice.removeMediaSource(mediaSourceLe)

        assertThat(mediaAudioDevice.getMediaProtocols().size).isEqualTo(0)
        assertThat(mediaAudioDevice.getMediaProtocols())
            .doesNotContain(MediaSource.Protocol.MCP_CLIENT)

        verify(mediaSourceLe, never()).unregisterCallback()

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Removing the currently preferred source should "invalidate" any pending requests by
     * indicating the value of the node has changed. If a client goes to request the value of the
     * media ID, the ID will not be recognized by the new preferred source, and will cause an error
     * code to be returned. Either BROWSING_NOT_SUPPORTED if the new source doesn't have browisng,
     * or MEDIA_ID_INVALID if they do
     */
    @Test
    fun removeMediaSource_preferredSourceRemovedWhileAwaitingResult_nodeChangedSentRequestsReturnInvalidId() {
        val root = MediaSource.BrowseNode("TEST_ROOT_ID", null, false, true)
        val folder = createBrowseFolder(5)
        val requestResult =
            MediaSource.BrowseResult(null, MediaSource.BrowseStatus.DOWNLOAD_PENDING)

        // Establish a root, and queue up a DOWNLOAD_PENDING response for a request
        doReturn(root).whenever(mediaSourceLe).onGetRoot()
        doReturn(requestResult).whenever(mediaSourceLe).onBrowseRequest(any())
        addMediaSource(expectedCount = 1, mediaSourceLe)

        doReturn(root).whenever(mediaSourceClassic).onGetRoot()
        addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        // Make the request
        val request = MediaSource.BrowseRequest("TEST_MEDIA_ID")
        var result = mediaAudioDevice.browse(request)

        // Assert that its pending with the current folder results
        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.DOWNLOAD_PENDING)
        assertThat(result.results).isNull()

        // Remove the preferred source
        removeMediaSource(expectedCount = 1, mediaSourceLe)

        // Verify the node has changed to solicit re-fetches from clients, which should end up in
        // MEDIA_ID_INVALID later
        verify(deviceCallback).onBrowseNodeChanged("TEST_MEDIA_ID")

        // Verify the device root has changed
        verify(deviceCallback).onBrowseNodeChanged(mediaAudioDevice.getDeviceRoot().mediaId)

        // Verify requests for the node now no longer return a success status
        result = mediaAudioDevice.browse(request)
        assertThat(result.status).isNotEqualTo(MediaSource.BrowseStatus.SUCCESS)
    }

    // Remove - audio

    /* Test: Removing the final source when it's an audio source leaves an empty preferred source */
    @Test
    fun removeAudioSource_noMoreMediaSources_sourceRemovedCallbackRemovedPreferredSourceNull() {
        addAudioSource(expectedCount = 1, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()

        removeAudioSource(expectedCount = 0, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Removing the non-preferred source will not have an impact on the preferred source */
    @Test
    fun removeAudioSource_preferredSourceLeft_sourceRemovedCallbackRemovedPreferredSourceRemains() {
        addAudioSource(expectedCount = 1, audioSourceLe)
        addAudioSource(expectedCount = 2, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()

        removeAudioSource(expectedCount = 1, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Removing the preferred source will cause a new preferred source to be set */
    @Test
    fun removeAudioSource_nonPreferredSourceLeft_sourceRemovedCallbackRemovedPreferredSourceSet() {
        addAudioSource(expectedCount = 1, audioSourceLe)
        addAudioSource(expectedCount = 2, audioSourceClassic)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceLe)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()

        removeAudioSource(expectedCount = 1, audioSourceLe)

        assertThat(mediaAudioDevice.getPreferredAudioSource()).isEqualTo(audioSourceClassic)
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: Calling remove on a non-existent source is a safe no-op*/
    @Test
    fun removeAudioSource_sourceDoesNotExist_noOp() {
        mediaAudioDevice.removeAudioSource(audioSourceLe)

        assertThat(mediaAudioDevice.getAudioProtocols().size).isEqualTo(0)
        assertThat(mediaAudioDevice.getAudioProtocols())
            .doesNotContain(AudioSource.Protocol.BAP_UNICAST_SERVER)

        verify(audioSourceLe, never()).unregisterCallback()
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    // Preferred Source edge cases

    /* Test: getPreferredMediaSource should return null when no sources are connected */
    @Test
    fun getPreferredMediaSource_noSources_returnsNull() {
        assertThat(mediaAudioDevice.getPreferredMediaSource()).isNull()
    }

    /* Test: getPreferredAudioSource should return null when no sources are connected */
    @Test
    fun getPreferredAudioSource_noSources_returnsNull() {
        assertThat(mediaAudioDevice.getPreferredAudioSource()).isNull()
    }

    // Active Source Management

    /* Test: Setting a device as active with no sources is supported */
    @Test
    fun setActive_toTrueWithNoSources_stateSet() {
        mediaAudioDevice.setActiveSource(true)
        assertThat(mediaAudioDevice.isActiveSource()).isTrue()
    }

    /* Test: Setting a device as active with preferred sources prepares the audio source */
    @Test
    fun setActive_toTrueWithClassicSourcesAlreadyRegistered_stateSetAndSourcesNotified() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 1, audioSourceClassic)

        mediaAudioDevice.setActiveSource(true)

        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        verify(audioSourceClassic).prepare()
        verify(mediaSourceClassic, never()).onPause()
    }

    /* Test: Setting a device as active with preferred sources prepares the audio source, even with
     * more than one source available
     */
    @Test
    fun setActive_toTrueWithDualSourcesAlreadyRegistered_stateSetAndPreferredSourcesNotified() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 1, audioSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)
        addAudioSource(expectedCount = 2, audioSourceLe)

        mediaAudioDevice.setActiveSource(true)

        assertThat(mediaAudioDevice.isActiveSource()).isTrue()

        verify(audioSourceClassic).prepare()
        verify(mediaSourceClassic, never()).onPause()

        verify(audioSourceLe, never()).prepare()
        verify(mediaSourceLe, never()).onPause()
    }

    /* Test: When being set to inactive when we're not active, the operation should be a no-op */
    @Test
    fun setActive_toFalseWhileNotActive_noOp() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addAudioSource(expectedCount = 1, audioSourceClassic)
        clearInvocations(mediaSourceClassic)
        clearInvocations(audioSourceClassic)

        mediaAudioDevice.setActiveSource(false)
        assertThat(mediaAudioDevice.isActiveSource()).isFalse()

        verifyNoMoreInteractions(mediaSourceClassic)
        verifyNoMoreInteractions(audioSourceClassic)
    }

    /* Test: When becoming inactive, the preferred audio stream should be released and the preferred
     * Media source should be paused
     */
    @Test
    fun setActive_toFalseWhileActiveAndPlaying_sourcesReleasedAndPaused() {
        setActive_toTrueWithClassicSourcesAlreadyRegistered_stateSetAndSourcesNotified()

        val status = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, status)

        mediaAudioDevice.setActiveSource(false)
        assertThat(mediaAudioDevice.isActiveSource()).isFalse()

        verify(audioSourceClassic).release()
        verify(mediaSourceClassic).onPause()
    }

    /* Test: When becoming inactive, the preferred audio stream should be released and the preferred
     * Media source should be paused
     */
    @Test
    fun setActive_toFalseWhileActiveAndPaused_sourcesReleased() {
        setActive_toTrueWithClassicSourcesAlreadyRegistered_stateSetAndSourcesNotified()

        val status = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceClassic, status)

        mediaAudioDevice.setActiveSource(false)
        assertThat(mediaAudioDevice.isActiveSource()).isFalse()

        verify(audioSourceClassic).release()
        verify(mediaSourceClassic, never()).onPause()
    }

    // Facade: Metadata

    /* Test: The default value for metadata when there are no sources is null */
    @Test
    fun getMetadata_noSources_returnsNull() {
        assertThat(mediaAudioDevice.getMetadata()).isNull()
    }

    /* Test: The metadata value will always equal the value of the preferred source */
    @Test
    fun getMetadata_withOneSource_returnsPreferredSourceMetadata() {
        val metadata = createMetadata("TEST_TITLE_LE")
        setMetadataForSource(mediaSourceLe, metadata)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        assertThat(mediaAudioDevice.getMetadata()).isEqualTo(metadata)
    }

    /* Test: The metadata value will always equal the metadata of the preferred source, even with
     * more than one source
     */
    @Test
    fun getMetadata_withTwoSources_returnsPreferredSourceMetadata() {
        val metadataLe = createMetadata("TEST_TITLE_LE")
        setMetadataForSource(mediaSourceLe, metadataLe)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        val metadataClassic = createMetadata("TEST_TITLE_CLASSIC")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getMetadata()).isEqualTo(metadataLe)
    }

    // Facade: Playback Status

    /* Test: The default value for playback state when there are no sources is null */
    @Test
    fun getPlaybackState_noSources_returnsNull() {
        assertThat(mediaAudioDevice.getPlaybackStatus()).isNull()
    }

    /* Test: The playback state value will always equal the value of the preferred source */
    @Test
    fun getPlaybackState_withOneSource_returnsPreferredSourcePlaybackState() {
        val status = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, status)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        assertThat(mediaAudioDevice.getPlaybackStatus()).isEqualTo(status)
    }

    /* Test: The playback state value will always equal the value of the preferred source, even with
     * more than one source
     */
    @Test
    fun getPlaybackState_withTwoSources_returnsPreferredSourcePlaybackState() {
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        val statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getPlaybackStatus()).isEqualTo(statusLe)
    }

    // Facade: Now Playing List

    /* Test: The default value for the now playing list when there's no sources is an empty list */
    @Test
    fun getNowPlayingList_noSources_returnsEmptyList() {
        assertThat(mediaAudioDevice.getNowPlayingList()).isEmpty()
    }

    /* Test: The now playing queue value will always equal the value of the preferred source */
    @Test
    fun getNowPlayingList_withOneSource_returnsPreferredSourceNowPlayingList() {
        val queue = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceLe, queue)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        assertThat(mediaAudioDevice.getNowPlayingList()).isEqualTo(queue)
    }

    /* Test: The now playing queue value will always equal the value of the preferred source, even
     * with more than one source
     */
    @Test
    fun getNowPlayingList_withTwoSource_returnsPreferredSourceNowPlayingList() {
        val queueLe = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceLe, queueLe)
        addMediaSource(expectedCount = 1, mediaSourceLe)

        val queueClassic = createNowPlayingList(3) // different
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        assertThat(mediaAudioDevice.getNowPlayingList()).isEqualTo(queueLe)
    }

    // Facade: Stream State

    /* Test: The default stream state when no devices are connected is UNKNOWN */
    @Test
    fun getStreamState_noSources_returnsUnknown() {
        assertThat(mediaAudioDevice.getStreamState()).isEqualTo(AudioSource.StreamState.UNKNOWN)
    }

    /* Test: The stream state value will always equal the value of the preferred source */
    @Test
    fun getStreamState_withOneSource_returnsPreferredSourceStreamState() {
        setStreamStateForSource(audioSourceLe, AudioSource.StreamState.IDLE)
        addAudioSource(expectedCount = 1, audioSourceLe)

        assertThat(mediaAudioDevice.getStreamState()).isEqualTo(AudioSource.StreamState.IDLE)
    }

    /* Test: The stream state value will always equal the value of the preferred source, even with
     * more than one source
     */
    @Test
    fun getStreamState_withTwoSource_returnsPreferredSourceStreamState() {
        setStreamStateForSource(audioSourceLe, AudioSource.StreamState.IDLE)
        addAudioSource(expectedCount = 1, audioSourceLe)

        setStreamStateForSource(audioSourceClassic, AudioSource.StreamState.STREAMING)
        addAudioSource(expectedCount = 2, audioSourceClassic)

        assertThat(mediaAudioDevice.getStreamState()).isEqualTo(AudioSource.StreamState.IDLE)
    }

    // Playback Commands Routing

    /* Test: prepare() operations should be routed to the preferred source */
    @Test
    fun prepare_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.prepare()

        verify(mediaSourceClassic).onPrepare()
        verify(mediaSourceLe, never()).onPrepare()
    }

    /* Test: play() operations should be routed to the preferred source */
    @Test
    fun play_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceLe)
        addMediaSource(expectedCount = 2, mediaSourceClassic)

        mediaAudioDevice.play()

        verify(mediaSourceLe).onPlay()
        verify(mediaSourceClassic, never()).onPlay()
    }

    /* Test: pause() operations should be routed to the preferred source */
    @Test
    fun pause_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.pause()

        verify(mediaSourceClassic).onPause()
        verify(mediaSourceLe, never()).onPause()
    }

    /* Test: skipToNext() operations should be routed to the preferred source */
    @Test
    fun skipToNext_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.skipToNext()

        verify(mediaSourceClassic).onSkipToNext()
        verify(mediaSourceLe, never()).onSkipToNext()
    }

    /* Test: skipToPrevious() operations should be routed to the preferred source */
    @Test
    fun skipToPrevious_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.skipToPrevious()

        verify(mediaSourceClassic).onSkipToPrevious()
        verify(mediaSourceLe, never()).onSkipToPrevious()
    }

    /* Test: skipToQueueItem() operations should be routed to the preferred source */
    @Test
    fun skipToQueueItem_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.skipToQueueItem(0)

        verify(mediaSourceClassic).onSkipToQueueItem(0)
        verify(mediaSourceLe, never()).onSkipToQueueItem(any())
    }

    /* Test: stop() operations should be routed to the preferred source */
    @Test
    fun stop_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.stop()

        verify(mediaSourceClassic).onStop()
        verify(mediaSourceLe, never()).onStop()
    }

    /* Test: rewind() operations should be routed to the preferred source */
    @Test
    fun rewind_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.rewind()

        verify(mediaSourceClassic).onRewind()
        verify(mediaSourceLe, never()).onRewind()
    }

    /* Test: fastForward() operations should be routed to the preferred source */
    @Test
    fun fastForward_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.fastForward()

        verify(mediaSourceClassic).onFastForward()
        verify(mediaSourceLe, never()).onFastForward()
    }

    /* Test: playFromMediaId() operations should be routed to the preferred source */
    @Test
    fun playFromMediaId_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.playFromMediaId("TEST_MEDIA_ID")

        verify(mediaSourceClassic).onPlayFromMediaId("TEST_MEDIA_ID")
        verify(mediaSourceLe, never()).onPlayFromMediaId(any())
    }

    /* Test: setRepeatMode() operations should be routed to the preferred source */
    @Test
    fun setRepeatMode_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.setRepeatMode(MediaSource.RepeatMode.OFF)

        verify(mediaSourceClassic).onSetRepeatMode(MediaSource.RepeatMode.OFF)
        verify(mediaSourceLe, never()).onSetRepeatMode(any())
    }

    /* Test: setShuffleMode() operations should be routed to the preferred source */
    @Test
    fun setShuffleMode_preferredSourceSet_routedToPreferredSource() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)
        addMediaSource(expectedCount = 2, mediaSourceLe)

        mediaAudioDevice.setShuffleMode(MediaSource.ShuffleMode.OFF)

        verify(mediaSourceClassic).onSetShuffleMode(MediaSource.ShuffleMode.OFF)
        verify(mediaSourceLe, never()).onSetShuffleMode(any())
    }

    // Browsing Request/Result Routing

    // root

    /* Test: A device should always be able to provide a root definition, even if there's no sources
     * available. The expectation is that the root exists, but the contents may be empty
     */
    @Test
    fun getDeviceRoot_noSources_returnsRoot() {
        assertThat(mediaAudioDevice.getDeviceRoot()).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotEmpty()
    }

    /* Test: A device should always be able to provide a root definition, especially if there's
     * connected sources available.
     */
    @Test
    fun getDeviceRoot_preferredSourceSet_returnsRoot() {
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        assertThat(mediaAudioDevice.getDeviceRoot()).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotEmpty()
    }

    /* Test: A device instance should never return the same root media ID for different connection
     * instances. This helps obviate issues with MediaBrowser clients leaking node subscriptions.
     * where if a device reconnects some time later, browsers may fetch data because they've seen it
     * before (i.e. they still have a subscription), long before the user was even interested in the
     * node.
     */
    @Test
    fun getDeviceRoot_sameMacAddressDifferentInstances_returnsDifferentRootIds() {
        assertThat(mediaAudioDevice.getDeviceRoot()).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotNull()
        assertThat(mediaAudioDevice.deviceId).isNotEmpty()

        val mediaAudioDevice2 = MediaAudioDevice(device, deviceCallback)

        assertThat(mediaAudioDevice2.getDeviceRoot()).isNotNull()
        assertThat(mediaAudioDevice2.deviceId).isNotEmpty()

        assertThat(mediaAudioDevice.getDeviceRoot()).isNotEqualTo(mediaAudioDevice2.getDeviceRoot())
        assertThat(mediaAudioDevice.deviceId).isNotEqualTo(mediaAudioDevice2.deviceId)
    }

    // rest of the tree

    /* Test: Browse requests should be immediately return ERROR_NO_PREFERRED_MEDIA_SOURCE when there
     * is no preferred media source
     */
    @Test
    fun browse_noSources_returnsErrorNoPreferredSource() {
        val request = MediaSource.BrowseRequest("TEST_MEDIA_ID")
        val result = mediaAudioDevice.browse(request)

        assertThat(result.status)
            .isEqualTo(MediaSource.BrowseStatus.ERROR_NO_PREFERRED_MEDIA_SOURCE)
        assertThat(result.results).isNull()
    }

    /* Test: Requests for the root of a device should return ERROR_BROWSE_NOT_SUPPORTED if the
     * preferred source does not support browsing
     */
    @Test
    fun browse_getRootPreferredSourceDoesntSupportBrowsing_returnsErrorBrowseNotSupported() {
        doReturn(null).whenever(mediaSourceClassic).onGetRoot()
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        val request = MediaSource.BrowseRequest(mediaAudioDevice.deviceId)
        val result = mediaAudioDevice.browse(request)

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.ERROR_BROWSE_NOT_SUPPORTED)
        assertThat(result.results).isNull()
    }

    /* Test: Browse requests should be forwarded to the preferred source */
    @Test
    fun browse_getNodePreferredSourceRegistered_requestForwardedToSource() {
        val root = MediaSource.BrowseNode("TEST_ROOT_ID", null, false, true)
        val folder = createBrowseFolder(5)
        val requestResult = MediaSource.BrowseResult(folder, MediaSource.BrowseStatus.SUCCESS)

        doReturn(root).whenever(mediaSourceClassic).onGetRoot()
        doReturn(requestResult).whenever(mediaSourceClassic).onBrowseRequest(any())
        addMediaSource(expectedCount = 1, mediaSourceClassic)

        val request = MediaSource.BrowseRequest("TEST_MEDIA_ID")
        val result = mediaAudioDevice.browse(request)

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isEqualTo(folder)
    }

    // Processing Events from MediaSource Callbacks

    // metadata

    /* Test: onMetadataChanged events from a source should not impact the device state, nor be
     * forwarded to the server level when they do not belong to the preferred source
     */
    @Test
    fun onMetadataChanged_sourceNotPreferred_noOp() {
        val metadataLe = createMetadata("TEST_TITLE_LE")
        setMetadataForSource(mediaSourceLe, metadataLe)

        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        assertThat(mediaAudioDevice.getMetadata()).isEqualTo(metadataLe)

        val metadataClassic = createMetadata("TEST_TITLE_CLASSIC")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        callbackClassic.onMetadataChanged(metadataClassic)

        verify(deviceCallback, never()).onMetadataChanged(any())
        assertThat(mediaAudioDevice.getMetadata()).isEqualTo(metadataLe)
    }

    /* Test: onMetadataChanged events from a source should impact the device state and be forwarded
     * to the server level when they belong to the preferred source
     */
    @Test
    fun onMetadataChanged_sourcePreferred_callbackInvoked() {
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        val metadataLe = createMetadata("TEST_TITLE_LE")
        setMetadataForSource(mediaSourceLe, metadataLe)
        callbackLe.onMetadataChanged(metadataLe)

        verify(deviceCallback).onMetadataChanged(eq(metadataLe))
        assertThat(mediaAudioDevice.getMetadata()).isEqualTo(metadataLe)
    }

    // playback status

    /* Test: onPlaybackStatusChanged events from a source should not impact the device state, nor be
     * forwarded to the server level when they do not belong to the preferred source
     */
    @Test
    fun onPlaybackStatusChanged_sourceNotPreferred_noOp() {
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        assertThat(mediaAudioDevice.getPlaybackStatus()).isEqualTo(statusLe)

        val statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        callbackClassic.onPlaybackStatusChanged(statusClassic)

        verify(deviceCallback, never()).onPlaybackStatusChanged(any())
        assertThat(mediaAudioDevice.getPlaybackStatus()).isEqualTo(statusLe)
    }

    /* Test: onPlaybackStatusChanged events from a source should impact the device state and be
     * forwarded to the server level when they belong to the preferred source
     */
    @Test
    fun onPlaybackStatusChanged_sourcePreferred_callbackInvoked() {
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)
        callbackLe.onPlaybackStatusChanged(statusLe)

        verify(deviceCallback).onPlaybackStatusChanged(eq(statusLe))
        assertThat(mediaAudioDevice.getPlaybackStatus()).isEqualTo(statusLe)
    }

    // now playing list

    /* Test: onNowPlayingListChanged events from a source should not impact the device state, nor be
     * forwarded to the server level when they do not belong to the preferred source
     */
    @Test
    fun onNowPlayingListChanged_sourceNotPreferred_noOp() {
        val queueLe = createNowPlayingList(6)
        setNowPlayingListForSource(mediaSourceLe, queueLe)
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        assertThat(mediaAudioDevice.getNowPlayingList()).isEqualTo(queueLe)

        val queueClassic = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        callbackClassic.onNowPlayingListChanged(queueClassic)

        verify(deviceCallback, never()).onNowPlayingListChanged(any())
        assertThat(mediaAudioDevice.getNowPlayingList()).isEqualTo(queueLe)
    }

    /* Test: onNowPlayingListChanged events from a source should impact the device state and be
     * forwarded to the server level when they belong to the preferred source
     */
    @Test
    fun onNowPlayingListChanged_sourcePreferred_callbackInvoked() {
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        val queueLe = createNowPlayingList(6)
        setNowPlayingListForSource(mediaSourceLe, queueLe)
        callbackLe.onNowPlayingListChanged(queueLe)

        verify(deviceCallback).onNowPlayingListChanged(eq(queueLe))
        assertThat(mediaAudioDevice.getNowPlayingList()).isEqualTo(queueLe)
    }

    // browsing

    /* Test: browse node changed callbacks from a source should not be forwarded to the server level
     * when they do not belong to the preferred source.
     */
    @Test
    fun onBrowseNodeChanged_sourceNotPreferred_noOp() {
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        callbackClassic.onBrowseNodeChanged("MEDIA_ID_CLASSIC")

        verify(deviceCallback, never()).onBrowseNodeChanged(anyString())
    }

    /* Test: browse node changed callbacks from a source should be forwarded to the server level
     * when they belong to the preferred source.
     */
    @Test
    fun onBrowseNodeChanged_sourcePreferred_callbackInvoked() {
        val callbackLe = addMediaSource(expectedCount = 1, mediaSourceLe)
        val callbackClassic = addMediaSource(expectedCount = 2, mediaSourceClassic)
        clearInvocations(deviceCallback)

        callbackLe.onBrowseNodeChanged("TEST_TITLE_LE")

        verify(deviceCallback).onBrowseNodeChanged(eq("TEST_TITLE_LE"))
    }

    // Processing Events from AudioSource Callbacks

    /* Test: Stream state callbacks from a source should not impact the device state, nor be
     * forwarded to the server level when they do not belong to the preferred source.
     */
    @Test
    fun onStreamStateChanged_sourceNotPreferred_courtesyPauseSent() {
        val stateLe = AudioSource.StreamState.STREAMING
        setStreamStateForSource(audioSourceLe, stateLe)
        val callbackLe = addAudioSource(expectedCount = 1, audioSourceLe)
        val callbackClassic = addAudioSource(expectedCount = 2, audioSourceClassic)
        clearInvocations(deviceCallback)

        val stateClassic = AudioSource.StreamState.OPEN
        callbackClassic.onStreamStateChanged(stateClassic)

        verify(deviceCallback, never()).onAudioStreamStateChanged(any())
        assertThat(mediaAudioDevice.getStreamState()).isEqualTo(stateLe)
    }

    /* Test: Stream state callbacks from a source should impact the device state and be forwarded to
     * the server level when they belong to the preferred source.
     */
    @Test
    fun onStreamStateChanged_sourcePreferred_callbackInvoked() {
        val callbackLe = addAudioSource(expectedCount = 1, audioSourceLe)
        val callbackClassic = addAudioSource(expectedCount = 2, audioSourceClassic)
        clearInvocations(deviceCallback)

        val stateLe = AudioSource.StreamState.OPEN
        setStreamStateForSource(audioSourceLe, stateLe)
        callbackLe.onStreamStateChanged(stateLe)

        verify(deviceCallback).onAudioStreamStateChanged(eq(stateLe))
        assertThat(mediaAudioDevice.getStreamState()).isEqualTo(stateLe)
    }

    // ToString

    /* Test: toString should provide some content by default and should not crash */
    @Test
    fun toString_doesNotCrash() {
        assertThat(mediaAudioDevice.toString()).isNotEmpty()
    }

    /* Test: dump should provide some content by default and should not crash */
    @Test
    fun dump_doesNotCrash() {
        assertThat(mediaAudioDevice.dump()).isNotEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // Utility Functions
    // ---------------------------------------------------------------------------------------------

    private fun addMediaSource(expectedCount: Int, source: MediaSource): MediaSource.Callback {
        mediaAudioDevice.addMediaSource(source)

        assertThat(mediaAudioDevice.getMediaProtocols().size).isEqualTo(expectedCount)
        assertThat(mediaAudioDevice.getMediaProtocols()).contains(source.protocol)

        val callbackCaptor = argumentCaptor<MediaSource.Callback>()
        verify(source).registerCallback(callbackCaptor.capture())
        return callbackCaptor.firstValue
    }

    private fun addAudioSource(expectedCount: Int, source: AudioSource): AudioSource.Callback {
        mediaAudioDevice.addAudioSource(source)

        assertThat(mediaAudioDevice.getAudioProtocols().size).isEqualTo(expectedCount)
        assertThat(mediaAudioDevice.getAudioProtocols()).contains(source.protocol)

        val callbackCaptor = argumentCaptor<AudioSource.Callback>()
        verify(source).registerCallback(callbackCaptor.capture())
        return callbackCaptor.firstValue
    }

    private fun removeMediaSource(expectedCount: Int, source: MediaSource) {
        mediaAudioDevice.removeMediaSource(source)

        assertThat(mediaAudioDevice.getMediaProtocols().size).isEqualTo(expectedCount)
        assertThat(mediaAudioDevice.getMediaProtocols()).doesNotContain(source.protocol)

        verify(source).unregisterCallback()
    }

    private fun removeAudioSource(expectedCount: Int, source: AudioSource) {
        mediaAudioDevice.removeAudioSource(source)

        assertThat(mediaAudioDevice.getAudioProtocols().size).isEqualTo(expectedCount)
        assertThat(mediaAudioDevice.getAudioProtocols()).doesNotContain(source.protocol)

        verify(source).unregisterCallback()
    }

    private fun createMetadata(title: String): MediaSource.Metadata {
        return MediaSource.Metadata(title, "", "", 0, 0, "", 0, null)
    }

    private fun setMetadataForSource(source: MediaSource, metadata: MediaSource.Metadata) {
        doReturn(metadata).whenever(source).getMetadata()
    }

    private fun createPlaybackStatus(state: MediaSource.PlaybackState): MediaSource.PlaybackStatus {
        return MediaSource.PlaybackStatus(
            state,
            0,
            1.0f,
            0,
            emptyList(),
            MediaSource.ShuffleMode.OFF,
            MediaSource.RepeatMode.OFF,
        )
    }

    private fun setPlaybackStatusForSource(source: MediaSource, state: MediaSource.PlaybackStatus) {
        doReturn(state).whenever(source).getPlaybackStatus()
    }

    private fun createNowPlayingList(size: Int): List<MediaSource.Metadata> {
        val queue: MutableList<MediaSource.Metadata> = mutableListOf()
        for (i in 0 until size) {
            queue.add(createMetadata(size.toString()))
        }
        return queue
    }

    private fun setNowPlayingListForSource(source: MediaSource, list: List<MediaSource.Metadata>) {
        doReturn(list).whenever(source).getNowPlayingList()
    }

    private fun setStreamStateForSource(source: AudioSource, state: AudioSource.StreamState) {
        doReturn(state).whenever(source).getStreamState()
    }

    private fun createBrowseFolder(size: Int): List<MediaSource.BrowseNode>? {
        val folder: MutableList<MediaSource.BrowseNode> = mutableListOf()
        for (i in 0 until size) {
            val node = MediaSource.BrowseNode(i.toString(), null, false, true)
            folder.add(node)
        }
        return folder
    }
}
