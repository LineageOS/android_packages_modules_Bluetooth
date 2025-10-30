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

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.android.bluetooth.TestUtils
import com.android.bluetooth.a2dpsink.A2dpSinkService
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.tests.bluetooth.StaticMockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// TODO: BT MBS asserts?

@RunWith(AndroidJUnit4::class)
class MediaAudioServerTest {
    @get:Rule
    val staticMockitoRule =
        StaticMockitoRule(
            AvrcpControllerService::class.java,
            A2dpSinkService::class.java,
            Executors::class.java,
            MediaPlayer::class.java,
        )
    @get:Rule val browserServiceTestRule = ServiceTestRule()

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockAudioManager: AudioManager
    @Mock private lateinit var mockMediaPlayer: TestMediaPlayer

    private val device = TestUtils.getTestDevice(12)
    private lateinit var mediaSourceClassic: MediaSource
    private lateinit var mediaSourceLe: MediaSource
    private lateinit var audioSourceClassic: AudioSource
    private lateinit var audioSourceLe: AudioSource

    private val device2 = TestUtils.getTestDevice(34)
    private lateinit var mediaSourceClassic2: MediaSource
    private lateinit var mediaSourceLe2: MediaSource
    private lateinit var audioSourceClassic2: AudioSource
    private lateinit var audioSourceLe2: AudioSource

    private lateinit var mediaAudioServer: MediaAudioServer

    @Before
    fun setUp() {
        ExtendedMockito.doAnswer {
                val executor = TestExecutor()
                executor.startAutoDispatch()
                executor
            }
            .`when` { Executors.newSingleThreadExecutor() }

        doReturn(mockAudioManager)
            .whenever(mockContext)
            .getSystemService(eq(AudioManager::class.java))
        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        doReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            .whenever(mockAudioManager)
            .abandonAudioFocus(any())

        ExtendedMockito.doReturn(mockMediaPlayer).`when` {
            MediaPlayer.create(any(), any(), any(), any())
        }

        mediaSourceClassic = spy(MediaSource(device, MediaSource.Protocol.AVRCP_CONTROLLER))
        audioSourceClassic = spy(AudioSource(device, AudioSource.Protocol.A2DP_SINK))
        mediaSourceLe = spy(MediaSource(device, MediaSource.Protocol.MCP_CLIENT))
        audioSourceLe = spy(AudioSource(device, AudioSource.Protocol.BAP_UNICAST_SERVER))

        mediaSourceClassic2 = spy(MediaSource(device2, MediaSource.Protocol.AVRCP_CONTROLLER))
        audioSourceClassic2 = spy(AudioSource(device2, AudioSource.Protocol.A2DP_SINK))
        mediaSourceLe2 = spy(MediaSource(device2, MediaSource.Protocol.MCP_CLIENT))
        audioSourceLe2 = spy(AudioSource(device2, AudioSource.Protocol.BAP_UNICAST_SERVER))

        // We mock context so the server under test doesn't actually start this. Now we can start it
        // and check changes to the fields on our own to assert the right data was passed
        val bluetoothBrowserMediaServiceStartIntent: Intent =
            TestUtils.prepareIntentToStartBluetoothBrowserMediaService()
        browserServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent)

        // Ensure our MediaBrowserService starts with a blank state
        BluetoothMediaBrowserService.reset()
    }

    // Lifecycle

    // isEnabled

    /* Test: MediaAudioServer should be enabled when Media is enabled and Audio is not */
    @Test
    fun isEnabled_mediaEnabled_isEnabledTrue() {
        ExtendedMockito.doReturn(true).`when` { AvrcpControllerService.isEnabled() }
        ExtendedMockito.doReturn(false).`when` { A2dpSinkService.isEnabled() }

        assertThat(MediaAudioServer.isEnabled()).isTrue()
    }

    /* Test: MediaAudioServer should be enabled when Audio is enabled and Media is not */
    @Test
    fun isEnabled_audioEnabled_isEnabledTrue() {
        ExtendedMockito.doReturn(false).`when` { AvrcpControllerService.isEnabled() }
        ExtendedMockito.doReturn(true).`when` { A2dpSinkService.isEnabled() }

        assertThat(MediaAudioServer.isEnabled()).isTrue()
    }

    /* Test: MediaAudioServer should be enabled when both Media and Audio are enabled */
    @Test
    fun isEnabled_mediaAndAudioEnabled_isEnabledTrue() {
        ExtendedMockito.doReturn(true).`when` { AvrcpControllerService.isEnabled() }
        ExtendedMockito.doReturn(true).`when` { A2dpSinkService.isEnabled() }

        assertThat(MediaAudioServer.isEnabled()).isTrue()
    }

    /* Test: MediaAudioServer should not be enabled when Media and Audio are both disabled */
    @Test
    fun isEnabled_nothingEnabled_isEnabledFalse() {
        ExtendedMockito.doReturn(false).`when` { AvrcpControllerService.isEnabled() }
        ExtendedMockito.doReturn(false).`when` { A2dpSinkService.isEnabled() }

        assertThat(MediaAudioServer.isEnabled()).isFalse()
    }

    // create

    /* Test: MediaAudioServer should not start our MediaBrowserService when only Audio is enabled */
    @Test
    fun createServer_audioEnabled_audioOnlyServerCreated() {
        mediaAudioServer = makeServer(mediaEnabled = false, audioEnabled = true)

        verify(mockContext, never()).startService(ArgumentMatchers.any(Intent::class.java))
    }

    /* Test: MediaAudioServer should start our MediaBrowserService when only Media is enabled */
    @Test
    fun createServer_mediaEnabled_mediaOnlyServerCreated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = false)

        val intentCaptor = argumentCaptor<Intent>()
        verify(mockContext).startService(intentCaptor.capture())
        assertThat(intentCaptor.firstValue?.getComponent()?.getClassName())
            .isEqualTo(BluetoothMediaBrowserService::class.java.getName())
    }

    /* Test: MediaAudioServer should start our MediaBrowserService when Media.Audio are enabled */
    @Test
    fun createServer_audioAndMediaEnabled_mediaAudioServerCreated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val intentCaptor = argumentCaptor<Intent>()
        verify(mockContext).startService(intentCaptor.capture())
        assertThat(intentCaptor.firstValue?.getComponent()?.getClassName())
            .isEqualTo(BluetoothMediaBrowserService::class.java.getName())
    }

    // clean up

    /* Test: An Audio-only MediaAudioServer does not need to stop a MediaBrowserService */
    @Test
    fun cleanup_audioEnabled_browserServiceUntouched() {
        mediaAudioServer = makeServer(mediaEnabled = false, audioEnabled = true)

        mediaAudioServer.cleanup()

        verify(mockContext, never()).stopService(ArgumentMatchers.any(Intent::class.java))
    }

    /* Test: A Media-only MediaAudioServer needs to stop its MediaBrowserService */
    @Test
    fun cleanup_mediaEnabled_browserServiceStopped() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = false)

        mediaAudioServer.cleanup()

        val intentCaptor = argumentCaptor<Intent>()
        verify(mockContext).stopService(intentCaptor.capture())
        assertThat(intentCaptor.firstValue?.getComponent()?.getClassName())
            .isEqualTo(BluetoothMediaBrowserService::class.java.getName())
    }

    /* Test: A Media/Audio enabled MediaAudioServer needs to stop its MediaBrowserService */
    @Test
    fun cleanup_audioAndMediaEnabled_browserServiceStopped() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.cleanup()

        val intentCaptor = argumentCaptor<Intent>()
        verify(mockContext).stopService(intentCaptor.capture())
        assertThat(intentCaptor.firstValue?.getComponent()?.getClassName())
            .isEqualTo(BluetoothMediaBrowserService::class.java.getName())
    }

    // register sources + impact to active and preferred devices/sources and facade

    // media

    /* Test: Registering a media source for a new device should create a new device object to
     * manage that device's sources and register a callback for that particular source. When there
     * were no previous devices, the first created device will become active, even if it's a classic
     * source. The first registered source will be preferred
     */
    @Test
    fun registerMediaSource_classicSource_deviceMadeCallbackRegistered() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        verify(mediaSourceClassic).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceClassic)
    }

    /* Test: Registering a media source for a new device should create a new device object to
     * manage that device's sources and register a callback for that particular source. When there
     * were no previous devices, the first created device will become active. The first created
     * source will be preferred
     */
    @Test
    fun registerMediaSource_leSource_deviceMadeCallbackRegistered() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)

        verify(mediaSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
    }

    /* Test: Registering a second media source for a device should add that source to the existing
     * device object, and register a callback for the source. The existing source will remain the
     * preferred source.
     */
    @Test
    fun registerMediaSource_classicThenLeSource_deviceMadeCallbacksRegisteredClassicPreferred() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceClassic)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        verify(mediaSourceClassic).registerCallback(any())
        verify(mediaSourceLe).registerCallback(any())

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceClassic)
    }

    /* Test: Registering a second media source for a device should add that source to the existing
     * device object, and register a callback for the source. The existing source will remain the
     * preferred source.
     */
    @Test
    fun registerMediaSource_leThenClassicSource_deviceMadeCallbacksRegisteredLePreferred() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        verify(mediaSourceLe).registerCallback(any())
        verify(mediaSourceClassic).registerCallback(any())

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
    }

    /* Test: Registering a source for a second/new device should create a new device to manage
     * sources from that device, without impacting any other devices. The first device remains the
     * active device.
     */
    @Test
    fun registerMediaSource_sameTransportDifferentDevice_firstDeviceActive() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        verify(mediaSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)

        mediaAudioServer.registerMediaSource(mediaSourceLe2)
        verify(mediaSourceLe2).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
    }

    // audio

    /* Test: Registering an audio source for a new device should create a new device object to
     * manage that device's sources and register a callback for that particular source. When there
     * were no previous devices, the first created device will become active, even if it's a classic
     * source. The first registered source will be preferred
     */
    @Test
    fun registerAudioSource_classicSource_deviceMadeCallbackRegistered() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceClassic)

        verify(audioSourceClassic).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)
    }

    /* Test: Registering an audio source for a new device should create a new device object to
     * manage that device's sources and register a callback for that particular source. When there
     * were no previous devices, the first created device will become active. The first registered
     * source will be preferred
     */
    @Test
    fun registerAudioSource_leSource_deviceMadeCallbackRegistered() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)

        verify(audioSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Registering a second audio source for a device should add that source to the existing
     * device object, and register a callback for the source. The existing source will remain the
     * preferred source.
     */
    @Test
    fun registerAudioSource_classicThenLeSource_deviceMadeCallbacksRegisteredClassicPreferred() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceClassic)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        verify(audioSourceClassic).registerCallback(any())
        verify(audioSourceLe).registerCallback(any())

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)
    }

    /* Test: Registering a second audio source for a device should add that source to the existing
     * device object, and register a callback for the source. The existing source will remain the
     * preferred source.
     */
    @Test
    fun registerAudioSource_leThenClassicSource_deviceMadeCallbacksRegisteredLePreferred() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceClassic)

        verify(audioSourceClassic).registerCallback(any())
        verify(audioSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Registering a source for a second/new device should create a new device to manage
     * sources from that device, without impacting any other devices. The first device remains the
     * active device.
     */
    @Test
    fun registerAudioSource_sameTransportDifferentDevice_firstDeviceActive() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)
        verify(audioSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)

        mediaAudioServer.registerAudioSource(audioSourceLe2)
        verify(audioSourceLe2).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    // mix/match

    /* Test: You can registered media and audio sources for the same device. Both will have
     * callbacks registered, and the device will be active.
     */
    @Test
    fun registerSources_mediaAudioSameDevice_deviceCreatedAndActive() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        verify(mediaSourceLe).registerCallback(any())
        verify(audioSourceLe).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Creation of device objects is source type agnostic, as is the active device assignment.
     * All sources get callbacks and the first device source to connect will be active
     */
    @Test
    fun registerSources_mediaAudioDifferentDevice_manyDevicesCreatedFirstActive() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceLe2)

        verify(mediaSourceLe).registerCallback(any())
        verify(audioSourceLe2).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource()).isNull()
    }

    /* Test: Creation of device objects is source type agnostic, as is the active device assignment.
     * All sources get callbacks and the first device source to connect will be active. Prove this
     * in the typical, full dual mode situation
     */
    @Test
    fun registerSources_fullDualModeDifferentDevice_manyDevicesCreatedFirstActive() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        verify(mediaSourceLe).registerCallback(any())
        verify(mediaSourceLe2).registerCallback(any())

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource()).isNull()

        mediaAudioServer.registerAudioSource(audioSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceLe2)

        verify(audioSourceLe).registerCallback(any())
        verify(audioSourceLe2).registerCallback(any())

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Validate that we can use different transports for our media and audio preferences */
    @Test
    fun registerSources_leAudioAndClassicMedia_mixedPreferredSourceTransportsSupported() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        verify(audioSourceLe).registerCallback(any())
        verify(mediaSourceClassic).registerCallback(any())
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceClassic)
    }

    // unregister sources + impact to active and preferred devices/sources and facade

    // media

    /* Test: Unregistering the last media source when an audio source still exists for a device
     * should keep the device, but remove the preferred media transport, setting it to null
     */
    @Test
    fun unregisterMediaSource_noMoreMediaSources_sourceRemovedCallbackRemovedPreferredSourceNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceClassic)
        mediaAudioServer.registerAudioSource(audioSourceClassic)
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)

        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)
        verify(mediaSourceClassic).unregisterCallback()
        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource()).isNull()
    }

    /* Test: Unregistering the unpreferred media source should keep the device and the preferred
     * source
     */
    @Test
    fun unregisterMediaSource_unpreferredSourceRemoved_sourceRemovedCallbackRemovedPreferredSourceRemains() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)

        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)

        verify(mediaSourceClassic).unregisterCallback()
        verify(mediaSourceLe, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
    }

    /* Test: Unregistering the preferred media source when there's other unpreferred sources should
     * keep the device and update the preferred source to the first remaining available media source
     */
    @Test
    fun unregisterMediaSource_preferredSourceRemoved_sourceRemovedCallbackRemovedPreferredSourceSet() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)

        mediaAudioServer.unregisterMediaSource(mediaSourceLe)

        verify(mediaSourceLe).unregisterCallback()
        verify(mediaSourceClassic, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceClassic)
    }

    /* Test: Unregistering a source that isn't registered should be a safe no-op */
    @Test
    fun unregisterMediaSource_sourceDoesNotExist_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)

        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)

        verify(mediaSourceClassic, never()).unregisterCallback()
        verify(mediaSourceLe, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)
    }

    /* Test: unregistering the final source for a device should clean up that device. If that device
     * is active, we should be left with no active device, even if there are other devices available
     * (the UI will drive the active device value through browsing)
     */
    @Test
    fun unregisterMediaSource_activeDeviceLastSource_activeDeviceNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredMediaSource())
            .isEqualTo(mediaSourceLe)

        mediaAudioServer.unregisterMediaSource(mediaSourceLe)

        verify(mediaSourceLe).unregisterCallback()
        assertThat(mediaAudioServer.getActiveDevice()).isNull()
    }

    // audio

    /* Test: Unregistering the last audio source when a media source still exists for a device
     * should keep the device, but remove the preferred audio transport, setting it to null
     */
    @Test
    fun unregisterAudioSource_noMoreAudioSources_sourceRemovedCallbackRemovedPreferredSourceNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceClassic)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)

        mediaAudioServer.unregisterAudioSource(audioSourceClassic)

        verify(audioSourceClassic).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource()).isNull()
    }

    /* Test: Unregistering the unpreferred audio source should keep the device and the preferred
     * source
     */
    @Test
    fun unregisterAudioSource_unpreferredSourceRemoved_sourceRemovedCallbackRemovedPreferredSourceRemains() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceClassic)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)

        mediaAudioServer.unregisterAudioSource(audioSourceLe)

        verify(audioSourceLe).unregisterCallback()
        verify(audioSourceClassic, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)
    }

    /* Test: Unregistering the preferred audio source when there's other unpreferred sources should
     * keep the device and update the preferred source to the first remaining available audio source
     */
    @Test
    fun unregisterAudioSource_preferredSourceRemoved_sourceRemovedCallbackRemovedPreferredSourceSet() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceClassic)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceClassic)

        mediaAudioServer.unregisterAudioSource(audioSourceClassic)

        verify(audioSourceClassic).unregisterCallback()
        verify(audioSourceLe, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Unregistering an audio source that hasn't been registered should be a safe no-op */
    @Test
    fun unregisterAudioSource_sourceDoesNotExist_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)

        mediaAudioServer.unregisterAudioSource(audioSourceClassic)

        verify(audioSourceClassic, never()).unregisterCallback()
        verify(audioSourceLe, never()).unregisterCallback()

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)
    }

    /* Test: Unregistering an audio source that was the last source for a device should clean up
     * that device. If that device active, the active device should be cleared and set to null.
     */
    @Test
    fun unregisterAudioSource_activeDeviceLastSource_activeDeviceNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerAudioSource(audioSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceLe2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getActiveDevice()?.getPreferredAudioSource())
            .isEqualTo(audioSourceLe)

        mediaAudioServer.unregisterAudioSource(audioSourceLe)

        verify(audioSourceLe).unregisterCallback()
        assertThat(mediaAudioServer.getActiveDevice()).isNull()
    }

    // Audio Focus

    // getAudioFocusState

    /* Test: The default state for audio focus is NONE */
    @Test
    fun getAudioFocusState_getDefaultStatus_statusIsNone() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    // requestAudioFocus

    /* Test: When we request and receive audio focus, our state should be GAIN and our silent media
     * player should briefly play to ensure we can be routed media key events.
     */
    @Test
    fun requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain() {
        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        mediaAudioServer.prepare() // Causes us to request focus indirectly

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        verify(mockMediaPlayer).start()
    }

    /* Test: When we request audio focus and fail to receive it, our state should remain NONE. We
     * should never have used our silent media player. When there's no sources, no decisions are
     * made about pausing playback.
     */
    @Test
    fun requestAudioFocus_notGrantedNoSources_nothingHappens() {
        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.prepare() // Causes us to request focus indirectly

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    /* Test: When we request audio focus and fail to receive it, our state should remain NONE. We
     * should never have used our silent media player. If we were paused, our active/preferred media
     * source should not be sent a pause
     */
    @Test
    fun requestAudioFocus_notGrantedWhilePaused_nothingHappens() {
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.prepare() // Causes us to request focus indirectly

        verify(mediaSourceLe, never()).onPause()

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    /* Test: When we request audio focus and fail to receive it, our state should remain NONE. We
     * should never have used our silent media player. If we were playing, our active/preferred
     * media source should be sent a pause
     */
    @Test
    fun requestAudioFocus_notGrantedWhilePlaying_pauseSent() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)
        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.prepare() // Causes us to request focus indirectly

        verify(mediaSourceLe).onPause()

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
        verify(mediaSourceLe, never()).onPrepare()
    }

    // onAudioFocusStateChanged

    /* Test: Upon losing audio focus, we should indicate to the active/preferred audio source that
     * their stream should be suspended (stopped or gain set to 0, etc.). If we were in a playing
     * media playback state, a pause is sent. We should always abandon focus when we lose it. Our
     * silence media player should be cleaned up.
     */
    @Test
    fun onAudioFocusChanged_gainToLossWhilePlaying_streamSuspendedFocusAbandonedPauseSent() {
        requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain()

        // Set playing state
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        // Send a LOSS event
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS)

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)

        verify(audioSourceLe).suspend()
        verify(mediaSourceLe).onPause()

        verify(mockMediaPlayer).stop()
        verify(mockMediaPlayer).release()
    }

    /* Test: Upon losing audio focus, we should indicate to the active/preferred audio source that
     * their stream should be suspended (stopped or gain set to 0, etc.). If we were in a paused
     * media playback state, no pause is sent. We should always abandon focus when we lose it. Our
     * silence media player should be cleaned up.
     */
    @Test
    fun onAudioFocusChanged_gainToLossWhilePaused_streamSuspendedFocusAbandonedNoPause() {
        requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain()

        // Set paused state
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        // Send a LOSS event
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS)

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)

        verify(audioSourceLe).suspend()
        verify(mediaSourceLe, never()).onPause()

        verify(mockMediaPlayer).stop()
        verify(mockMediaPlayer).release()
    }

    /* Test: Upon entering a transient loss state, the active/preferred audio source will be
     * temporarily suspended. If we were playing, the active/preferred media source will be sent a
     * pause. Our silence media player should not be cleaned up yet, as the loss is transient
     */
    @Test
    fun onAudioFocusChanged_gainToTransientLossWhilePlaying_streamSuspendedPauseSent() {
        requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain()

        // Set playing state
        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        // Send a LOSS_TRANSIENT
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        verify(audioSourceLe).suspend()
        verify(mediaSourceLe).onPause()

        verify(mockMediaPlayer, never()).stop()
        verify(mockMediaPlayer, never()).release()
    }

    /* Test: Upon entering a transient loss state, the active/preferred audio source will be
     * temporarily suspended. If we were paused, the active/preferred media source will not be sent
     * a pause. Our silence media player should not be cleaned up yet, as the loss is transient
     */
    @Test
    fun onAudioFocusChanged_gainToTransientLossWhilePaused_streamSuspendedNoPause() {
        requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain()

        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)

        // Send a LOSS_TRANSIENT
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        verify(audioSourceLe).suspend()
        verify(mediaSourceLe, never()).onPause()

        verify(mockMediaPlayer, never()).stop()
        verify(mockMediaPlayer, never()).release()
    }

    /* Test: Upon exiting a transient loss back to a gain state, playback should be resumed if we
     * were previously playing and didn't receive an event indicating the user wants the stream to
     * be paused while we were in the loss.
     */
    @Test
    fun onAudioFocusChanged_transientLossToGainWasPlaying_streamResumesPlaySent() {
        onAudioFocusChanged_gainToTransientLossWhilePlaying_streamSuspendedPauseSent()
        clearInvocations(mockMediaPlayer)
        clearInvocations(audioSourceLe)

        // Send a GAIN
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        verify(mockMediaPlayer).start()

        verify(audioSourceLe).start()
        verify(mediaSourceLe).onPlay()
    }

    /* Test: Upon exiting a transient loss back to a gain state, playback should not be resumed if we
     * were previously playing and received an event indicating the user wants the stream to
     * be paused while we were in the loss (i.e. pause/stop).
     */
    @Test
    fun onAudioFocusChanged_transientLossToGainWasPlayingPauseReceived_streamResumesPlayNotSent() {
        onAudioFocusChanged_gainToTransientLossWhilePlaying_streamSuspendedPauseSent()
        clearInvocations(mockMediaPlayer)
        clearInvocations(audioSourceLe)

        mediaAudioServer.pause()

        // Send a GAIN
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        verify(mockMediaPlayer).start()

        verify(audioSourceLe).start()
        verify(mediaSourceLe, never()).onPlay()
    }

    /* Test: Upon exiting a transient loss back to a gain state, playback should not be resumed if we
     * were previously playing and received an event indicating the user wants the stream to
     * be paused while we were in the loss (i.e. pause/stop).
     */
    @Test
    fun onAudioFocusChanged_transientLossToGainWasPlayingStopReceived_streamResumesPlayNotSent() {
        onAudioFocusChanged_gainToTransientLossWhilePlaying_streamSuspendedPauseSent()
        clearInvocations(mockMediaPlayer)
        clearInvocations(audioSourceLe)

        mediaAudioServer.stop()

        // Send a GAIN
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        verify(mockMediaPlayer).start()

        verify(audioSourceLe).start()
        verify(mediaSourceLe, never()).onPlay()
    }

    /* Test: Upon exiting a transient loss to a full loss, the active/preferred audio source should
     * be told to suspend. The silence media player should be cleared up
     */
    @Test
    fun onAudioFocusChanged_transientLossToLoss_streamSuspendedFocusAbandoned() {
        onAudioFocusChanged_gainToTransientLossWhilePaused_streamSuspendedNoPause()
        clearInvocations(mockMediaPlayer)
        clearInvocations(audioSourceLe)

        // Send a GAIN
        // Note: The Audio stack function to get the OnAudioFocusChangeListener object is marked as
        // @TestApi, which cannot be accessed from these tests, as these are module tests. Use the
        // internal function instead as a work-a-round
        mediaAudioServer.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS)

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)

        verify(audioSourceLe).suspend()

        verify(mockMediaPlayer).stop()
        verify(mockMediaPlayer).release()
    }

    // abandonAudioFocus

    /* Test: Abandoning audio focus from a GAIN state should put us in a NONE state */
    @Test
    fun abandonAudioFocus_focusStateGain_focusAbandoned() {
        requestAudioFocus_granted_mediaPlayerPlaysStatusIsGain()

        mediaAudioServer.cleanup() // Causes us to abandon focus

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    /* Test: Abandoning audio focus from a LOSS state should leave us in a NONE state */
    @Test
    fun abandonAudioFocus_focusStateLoss_focusAbandoned() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.cleanup() // Causes us to abandon focus

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    /* Test: Abandoning audio focus from a transient LOSS state should put us in a NONE state */
    @Test
    fun abandonAudioFocus_focusStateTransientLoss_focusAbandonedNoOp() {
        onAudioFocusChanged_gainToTransientLossWhilePaused_streamSuspendedNoPause()

        mediaAudioServer.cleanup() // Causes us to abandon focus

        verify(mockAudioManager).abandonAudioFocus(any())
        assertThat(mediaAudioServer.getAudioFocusState()).isEqualTo(AudioManager.AUDIOFOCUS_NONE)
    }

    // Facade - Media

    /* Test: The default value for metadata with no sources is null */
    @Test
    fun getMetadata_noSources_returnsNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        assertThat(mediaAudioServer.getMetadata()).isNull()
    }

    /* Test: getMetadata should return the active/preferred media source's metadata */
    @Test
    fun getMetadata_oneDevice_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val metadataLe = createMetadata("LE_TITLE_1")
        setMetadataForSource(mediaSourceLe, metadataLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataLe)
    }

    /* Test: getMetadata should return the active/preferred media source's metadata, even when there
     * is more than one connected device
     */
    @Test
    fun getMetadata_manyDevices_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val metadataLe = createMetadata("LE_TITLE_1")
        setMetadataForSource(mediaSourceLe, metadataLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val metadataLe2 = createMetadata("LE_TITLE_2")
        setMetadataForSource(mediaSourceLe2, metadataLe2)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataLe)
    }

    // Facade - Playback State

    /* Test: The default value for playback state with no sources is null */
    @Test
    fun getPlaybackStatus_noSources_returnsNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        assertThat(mediaAudioServer.getPlaybackStatus()).isNull()
    }

    /* Test: getPlaybackStatus should return the active/preferred media source's playback state */
    @Test
    fun getPlaybackStatus_oneDevice_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusLe)
    }

    /* Test: getPlaybackStatus should return the active/preferred media source's playback state,
     * even when there's more than one device connected
     */
    @Test
    fun getPlaybackStatus_manyDevices_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val statusLe = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceLe, statusLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val statusLe2 = createPlaybackStatus(MediaSource.PlaybackState.STOPPED)
        setPlaybackStatusForSource(mediaSourceLe2, statusLe2)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusLe)
    }

    // Facade - Now Playing List

    /* Test: The default value for now playing queue with no sources is any empty list */
    @Test
    fun getNowPlayingList_noSources_returnsEmptyList() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        assertThat(mediaAudioServer.getNowPlayingList()).isNotNull()
        assertThat(mediaAudioServer.getNowPlayingList()).isEmpty()
    }

    /* Test: getNowPlayingList should return the active/preferred media source's queue */
    @Test
    fun getNowPlayingList_oneDevice_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val queueLe = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceLe, queueLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueLe)
    }

    /* Test: getNowPlayingList should return the active/preferred media source's queue, even when
     * there is more than one connected device
     */
    @Test
    fun getNowPlayingList_manyDevices_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val queueLe = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceLe, queueLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val queueLe2 = createNowPlayingList(3)
        setNowPlayingListForSource(mediaSourceLe2, queueLe2)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueLe)
    }

    // Facade - Audio Stream State

    /* Test: The default value for stream state with no sources is null */
    @Test
    fun getAudioStreamState_noSources_returnsNull() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        assertThat(mediaAudioServer.getStreamState()).isNull()
    }

    /* Test: getAudioStreamState should return the active/preferred audio source's stream state */
    @Test
    fun getAudioStreamState_oneDevice_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        setStreamStateForSource(audioSourceLe, AudioSource.StreamState.STREAMING)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)
    }

    /* Test: getAudioStreamState should return the active/preferred audio source's stream state,
     * even when there is more than one connected device
     */
    @Test
    fun getAudioStreamState_manyDevices_returnsActivePreferredDevicesMetadata() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        setStreamStateForSource(audioSourceLe, AudioSource.StreamState.STREAMING)
        mediaAudioServer.registerAudioSource(audioSourceLe)

        setStreamStateForSource(audioSourceLe2, AudioSource.StreamState.IDLE)
        mediaAudioServer.registerAudioSource(audioSourceLe2)

        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)
    }

    // Playback controls

    // prepare

    /* Test: The prepare operation should be a no-op with no active/preferred media source */
    @Test
    fun prepare_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.prepare()

        verify(mediaSourceLe, never()).onPrepare()
    }

    /* Test: The prepare operation should be routed to the active/preferred media source */
    @Test
    fun prepare_oneDeviceTwoSources_routedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.prepare()

        verify(mediaSourceLe).onPrepare()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The prepare operation should be routed to the active/preferred source, even with more
     * than one device connected
     */
    @Test
    fun prepare_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.prepare()

        verify(mediaSourceLe).onPrepare()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The prepare operation should be not routed to the active/preferred source if we fail to
     * get audio focus
     */
    @Test
    fun prepare_focusRequestFails_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.prepare()

        verify(mediaSourceLe, never()).onPrepare()
    }

    // play

    /* Test: The play() operation should be a no-op with no active/preferred media source */
    @Test
    fun play_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.play()

        verify(mediaSourceLe, never()).onPlay()
    }

    /* Test: The play() operation should be routed to the active/preferred media source */
    @Test
    fun play_oneDeviceTwoSources_routedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.play()

        verify(mediaSourceLe).onPlay()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The play() operation should be routed to the active/preferred media source, even with
     * more than one connected device
     */
    @Test
    fun play_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.play()

        verify(mediaSourceLe).onPlay()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The play() operation should not be routed to the active/preferred media source if we
     * fail to get audio focus
     */
    @Test
    fun play_focusRequestFails_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.play()

        verify(mediaSourceLe, never()).onPlay()
    }

    // pause

    /* Test: The pause() operation should be a no-op with no active/preferred source */
    @Test
    fun pause_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.pause()

        verify(mediaSourceLe, never()).onPause()
    }

    /* Test: The pause() operation should be routed to the active/preferred source */
    @Test
    fun pause_oneDeviceTwoSources_proutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.pause()

        verify(mediaSourceLe).onPause()
    }

    /* Test: The pause() operation should be routed to the active/preferred source, even when there
     * is more than one device connected
     */
    @Test
    fun pause_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.pause()

        verify(mediaSourceLe).onPause()
    }

    // skipToNext

    /* Test: The skipToNext() operation should be a no-op with no active/preferred source */
    @Test
    fun skipToNext_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.skipToNext()

        verify(mediaSourceLe, never()).onSkipToNext()
    }

    /* Test: The skipToNext() operation should be routed to the active/preferred source */
    @Test
    fun skipToNext_oneDeviceTwoSources_skipToroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.skipToNext()

        verify(mediaSourceLe).onSkipToNext()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToNext() operation should be routed to the active/preferred source, even when
     * there is more than one device connected
     */
    @Test
    fun skipToNext_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.skipToNext()

        verify(mediaSourceLe).onSkipToNext()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToNext() operation should not be routed to the active/preferred source if we
     * fail to get audio focus
     */
    @Test
    fun skipToNext_focusRequestFailed_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.skipToNext()

        verify(mediaSourceLe, never()).onSkipToNext()
    }

    // skipToPrevious

    /* Test: The skipToPrevious() operation should be a no-op with no active/preferred source */
    @Test
    fun skipToPrevious_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.skipToPrevious()

        verify(mediaSourceLe, never()).onSkipToPrevious()
    }

    /* Test: The skipToPrevious() operation should be routed to the active/preferred source */
    @Test
    fun skipToPrevious_oneDeviceTwoSources_skipToPrevroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.skipToPrevious()

        verify(mediaSourceLe).onSkipToPrevious()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToPrevious() operation should be routed to the active/preferred source, even
     * when there is more than one device connected
     */
    @Test
    fun skipToPrevious_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.skipToPrevious()

        verify(mediaSourceLe).onSkipToPrevious()
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToPrevious() operation should not be routed to the active/preferred source if
     * we fail to get audio focus
     */
    @Test
    fun skipToPrevious_focusRequestFailed_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.skipToPrevious()

        verify(mediaSourceLe, never()).onSkipToPrevious()
    }

    // skipToQueueItem

    /* Test: The skipToQueueItem() operation should be a no-op with no active/preferred source */
    @Test
    fun skipToQueueItem_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.skipToQueueItem(0)

        verify(mediaSourceLe, never()).onSkipToQueueItem(anyLong())
    }

    /* Test: The skipToQueueItem() operation should be routed to the active/preferred source */
    @Test
    fun skipToQueueItem_oneDeviceTwoSources_skipToQueueroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.skipToQueueItem(0)

        verify(mediaSourceLe).onSkipToQueueItem(eq(0))
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToQueueItem() operation should be routed to the active/preferred source, even
     * when there is more than one device connected
     */
    @Test
    fun skipToQueueItem_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.skipToQueueItem(0)

        verify(mediaSourceLe).onSkipToQueueItem(eq(0))
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The skipToQueueItem() operation should not be routed to the active/preferred source if
     * we fail to get audio focus
     */
    @Test
    fun skipToQueueItem_focusRequestFailed_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.skipToQueueItem(0)

        verify(mediaSourceLe, never()).onSkipToQueueItem(eq(0))
    }

    // stop

    /* Test: The stop() operation should be a no-op with no active/preferred source */
    @Test
    fun stop_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.stop()

        verify(mediaSourceLe, never()).onStop()
    }

    /* Test: The stop() operation should be routed to the active/preferred source */
    @Test
    fun stop_oneDeviceTwoSources_routedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.stop()

        verify(mediaSourceLe).onStop()
    }

    /* Test: The stop() operation should be routed to the active/preferred source, even when there
     * is more than one device connected
     */
    @Test
    fun stop_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.stop()

        verify(mediaSourceLe).onStop()
    }

    // rewind

    /* Test: The rewind() operation should be a no-op with no active/preferred source */
    @Test
    fun rewind_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.rewind()

        verify(mediaSourceLe, never()).onRewind()
    }

    /* Test: The rewind() operation should be routed to the active/preferred source */
    @Test
    fun rewind_oneDeviceTwoSources_reroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.rewind()

        verify(mediaSourceLe).onRewind()
    }

    /* Test: The rewind() operation should be routed to the active/preferred source, even when there
     * is more than one device connected
     */
    @Test
    fun rewind_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.rewind()

        verify(mediaSourceLe).onRewind()
    }

    // fastForward

    /* Test: The fastForward() operation should be a no-op with no active/preferred source */
    @Test
    fun fastForward_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.fastForward()

        verify(mediaSourceLe, never()).onFastForward()
    }

    /* Test: The fastForward() operation should be routed to the active/preferred source */
    @Test
    fun fastForward_oneDeviceTwoSources_fastForroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.fastForward()

        verify(mediaSourceLe).onFastForward()
    }

    /* Test: The fastForward() operation should be routed to the active/preferred source, even when
     * there is more than one device connected
     */
    @Test
    fun fastForward_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.fastForward()

        verify(mediaSourceLe).onFastForward()
    }

    // playFromMediaId

    /* Test: The playFromMediaId() operation should be a no-op with no active/preferred source */
    @Test
    fun playFromMediaId_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.playFromMediaId("MEDIA_ID")
    }

    /* Test: The playFromMediaId() operation should be routed to the active/preferred source */
    @Test
    fun playFromMediaId_oneDeviceTwoSources_playFromMedroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.playFromMediaId("MEDIA_ID")

        verify(mediaSourceLe).onPlayFromMediaId(eq("MEDIA_ID"))
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The playFromMediaId() operation should be routed to the active/preferred source, even
     * when there is more than one device connected
     */
    @Test
    fun playFromMediaId_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.playFromMediaId("MEDIA_ID")

        verify(mediaSourceLe).onPlayFromMediaId(eq("MEDIA_ID"))
        verify(mockAudioManager).requestAudioFocus(any())
    }

    /* Test: The playFromMediaId() operation should not be routed to the active/preferred source if
     * we fail to get audio focus
     */
    @Test
    fun playFromMediaId_focusRequestFailed_notRoutedToSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        mockFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        mediaAudioServer.playFromMediaId("MEDIA_ID")

        verify(mediaSourceLe, never()).onPlayFromMediaId(eq("MEDIA_ID"))
    }

    // setRepeatMode

    /* Test: The setRepeatMode() operation should be a no-op with no active/preferred source */
    @Test
    fun setRepeatMode_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.setRepeatMode(MediaSource.RepeatMode.OFF)

        verify(mediaSourceLe, never()).onSetRepeatMode(any())
    }

    /* Test: The setRepeatMode() operation should be routed to the active/preferred source */
    @Test
    fun setRepeatMode_oneDeviceTwoSources_setRepeatroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.setRepeatMode(MediaSource.RepeatMode.OFF)

        verify(mediaSourceLe).onSetRepeatMode(eq(MediaSource.RepeatMode.OFF))
    }

    /* Test: The setRepeatMode() operation should be routed to the active/preferred source, even
     * when there is more than one device connected
     */
    @Test
    fun setRepeatMode_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.setRepeatMode(MediaSource.RepeatMode.OFF)

        verify(mediaSourceLe).onSetRepeatMode(eq(MediaSource.RepeatMode.OFF))
    }

    // setShuffleMode

    /* Test: The setShuffleMode() operation should be a no-op with no active/preferred source */
    @Test
    fun setShuffleMode_noDevices_noOp() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.setShuffleMode(MediaSource.ShuffleMode.OFF)

        verify(mediaSourceLe, never()).onSetShuffleMode(any())
    }

    /* Test: The setShuffleMode() operation should be routed to the active/preferred source */
    @Test
    fun setShuffleMode_oneDeviceTwoSources_setShuffleroutedToPreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        mediaAudioServer.setShuffleMode(MediaSource.ShuffleMode.OFF)

        verify(mediaSourceLe).onSetShuffleMode(eq(MediaSource.ShuffleMode.OFF))
    }

    /* Test: The setShuffleMode() operation should be routed to the active/preferred source, even
     * when there is more than one device connected
     */
    @Test
    fun setShuffleMode_manyDevices_routedToActivePreferredSource() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        mediaAudioServer.setShuffleMode(MediaSource.ShuffleMode.OFF)

        verify(mediaSourceLe).onSetShuffleMode(eq(MediaSource.ShuffleMode.OFF))
    }

    // Browsing

    // getRoot

    /* Test: MediaAudioServer should still server a root, even when no devices are connected. It is
     * expected that new device nodes will be added under this root when they connect. The root
     * must be available statically, without a service instance, in case our MediaBrowserService
     * is up when the stack is down
     */
    @Test
    fun getRoot_noDevices_returnsRoot() {
        assertThat(MediaAudioServer.getRoot("package", null, null)).isNotNull()
    }

    /* Test: The root node itself is agnostic of the number of connected devices */
    @Test
    fun getRoot_oneDevice_returnsRoot() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val rootNoDevices = MediaAudioServer.getRoot("package", null, null)

        assertThat(rootNoDevices).isNotNull()

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceClassic)

        val rootOneDevice = MediaAudioServer.getRoot("package", null, null)

        assertThat(rootOneDevice).isNotNull()
        assertThat(rootOneDevice).isEqualTo(rootNoDevices)
    }

    /* Test: The root node itself is agnostic of the number of connected devices, even if there are
     * many connected
     */
    @Test
    fun getRoot_manyDevices_returnsSameRootAgnosticOfActivePreferredDevice() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val rootNoDevices = MediaAudioServer.getRoot("package", null, null)

        assertThat(rootNoDevices).isNotNull()

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        val rootDeviceOnePreferred = MediaAudioServer.getRoot("package", null, null)

        assertThat(rootDeviceOnePreferred).isNotNull()

        mediaAudioServer.unregisterMediaSource(mediaSourceLe)
        mediaAudioServer.unregisterMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val rootDeviceTwoPreferred = MediaAudioServer.getRoot("package", null, null)

        assertThat(rootDeviceTwoPreferred).isNotNull()
        assertThat(rootDeviceTwoPreferred).isEqualTo(rootDeviceOnePreferred)
        assertThat(rootDeviceTwoPreferred).isEqualTo(rootNoDevices)
    }

    // browse

    /* Test: Sending a browse request with a null request object is handled gracefully */
    @Test
    fun browse_requestIsNull_errorMediaIdInvalid() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        mediaAudioServer.registerMediaSource(mediaSourceLe)

        val result = mediaAudioServer.browse(null)

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID)
        assertThat(result.results).isNull()

        verify(mediaSourceLe, never()).onBrowseRequest(any())
    }

    /* Test: Sending a browse request when no devices are connected returns null results and an
     * error code indicating that we have no connected devices at the moment. This, combined with
     * an ERROR playback state helps MediaBrowser clients know we're in a recoverable "error" state
     * which will cause them to use our error resolution activity that helps them connect a device
     */
    @Test
    fun browse_requestRootWithNoDevice_errorNoDevicesConnected() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        val root = MediaAudioServer.getRoot("package", null, null)
        val result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.ERROR_NO_DEVICE_CONNECTED)
        assertThat(result.results).isNull()
    }

    /* Test: The root contents should contain a child node for each connected device */
    @Test
    fun browse_requestRootWithOneDevice_returnsOneDeviceNode() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)

        setRootNodeForSource(mediaSourceLe, "mediaSourceLe")
        val root = MediaAudioServer.getRoot("package", null, null)
        val result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isNotNull()
        assertThat(result.results!!.size).isEqualTo(1)

        val deviceNode = result.results!!.get(0)

        assertThat(deviceNode.mediaId).isNotEmpty()
    }

    /* Test: The root contents should contain a child node for each connected device, even when
     * multiple devices are connected
     */
    @Test
    fun browse_requestRootWithManyDevices_returnsManyDeviceNodes() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        setRootNodeForSource(mediaSourceLe, "mediaSourceLe")
        setRootNodeForSource(mediaSourceLe2, "mediaSourceLe2")
        val root = MediaAudioServer.getRoot("package", null, null)
        val result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isNotNull()
        assertThat(result.results!!.size).isEqualTo(2)

        var deviceNode = result.results!!.get(0)
        assertThat(deviceNode.mediaId).isNotEmpty()

        deviceNode = result.results!!.get(1)
        assertThat(deviceNode.mediaId).isNotEmpty()
    }

    /* Test: Sending a browse request for a node that a source has immediately available will
     * return the contents and a SUCCESS state
     */
    @Test
    fun browse_requestMediaIdNodeCached_returnsNode() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)

        setRootNodeForSource(mediaSourceLe, "mediaSourceLe")
        val root = MediaAudioServer.getRoot("package", null, null)
        var result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        val deviceId = result.results!!.get(0).mediaId
        val contents: List<MediaSource.BrowseNode> =
            listOf(
                MediaSource.BrowseNode("1", createMetadata("1"), true, false),
                MediaSource.BrowseNode("2", createMetadata("2"), true, false),
                MediaSource.BrowseNode("3", createMetadata("3"), true, false),
            )
        setBrowseNodeForSource(mediaSourceLe, "mediaSourceLe", contents)

        result = mediaAudioServer.browse(MediaSource.BrowseRequest(deviceId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isEqualTo(contents)
    }

    /* Test: Sending a browse request for a node that a source does not currently have available
     * return empty contents and a DOWNLOAD_PENDING state
     */
    @Test
    fun browse_requestMediaIdNodeNotCached_returnsPendingResult() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)

        setRootNodeForSource(mediaSourceLe, "mediaSourceLe")
        val root = MediaAudioServer.getRoot("package", null, null)
        var result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        val deviceId = result.results!!.get(0).mediaId
        setBrowseNodeForSource(
            mediaSourceLe,
            "mediaSourceLe",
            MediaSource.BrowseStatus.DOWNLOAD_PENDING,
        )

        result = mediaAudioServer.browse(MediaSource.BrowseRequest(deviceId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.DOWNLOAD_PENDING)
    }

    /* Test: MediaAudioServer is capable of routing received browse request to the right device when
     * many devices are connected, based on the responses from each device (ERROR_INVALID_MEDIA_ID
     * indicates a device doesn't recognize the requested ID, and that the server should try a
     * different source. Eventually, a source may recognize the node and service the request.
     */
    @Test
    fun browse_requestMediaIdManyDevicesNodeExists_returnsNodesForProperDevice() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        setRootNodeForSource(mediaSourceLe, "mediaSourceLe")
        setRootNodeForSource(mediaSourceLe2, "mediaSourceLe2")
        val root = MediaAudioServer.getRoot("package", null, null)
        var result = mediaAudioServer.browse(MediaSource.BrowseRequest(root.mediaId))

        // Device IDs have a UUID added to the back, but also always contain the MAC Address.
        // We can't use the results ordering, as there's no certain order, so do a fuzzy match
        var device1Id = result.results!!.get(0).mediaId
        var device2Id = result.results!!.get(1).mediaId
        if (!result.results!!.get(0).mediaId.contains(device.address)) {
            device1Id = result.results!!.get(1).mediaId
            device2Id = result.results!!.get(0).mediaId
        }

        val contents1: List<MediaSource.BrowseNode> =
            listOf(
                MediaSource.BrowseNode("1", createMetadata("1"), true, false),
                MediaSource.BrowseNode("2", createMetadata("2"), true, false),
                MediaSource.BrowseNode("3", createMetadata("3"), true, false),
            )
        setBrowseNodeForSource(mediaSourceLe, "mediaSourceLe", contents1)

        val contents2: List<MediaSource.BrowseNode> =
            listOf(
                MediaSource.BrowseNode("4", createMetadata("4"), true, false),
                MediaSource.BrowseNode("5", createMetadata("5"), true, false),
            )
        setBrowseNodeForSource(mediaSourceLe2, "mediaSourceLe2", contents2)

        result = mediaAudioServer.browse(MediaSource.BrowseRequest(device1Id))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isEqualTo(contents1)

        result = mediaAudioServer.browse(MediaSource.BrowseRequest(device2Id))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.SUCCESS)
        assertThat(result.results).isEqualTo(contents2)
    }

    /* Test: MediaAudioServer is capable of determining that no connected device recognizes a given
     * media ID and will return empty results with an ERROR_INVALID_MEDIA_ID result, even when
     * many devices are connected
     */
    @Test
    fun browse_requestMediaIdManyDevicesNodeDoesntExist_errorMediaIdInvalid() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        mediaAudioServer.registerMediaSource(mediaSourceLe)
        mediaAudioServer.registerMediaSource(mediaSourceLe2)

        val testMediaId = "MEDIA_ID"
        setBrowseNodeForSource(
            mediaSourceLe,
            testMediaId,
            MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID,
        )
        setBrowseNodeForSource(
            mediaSourceLe2,
            testMediaId,
            MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID,
        )

        val result = mediaAudioServer.browse(MediaSource.BrowseRequest(testMediaId))

        assertThat(result.status).isEqualTo(MediaSource.BrowseStatus.ERROR_MEDIA_ID_INVALID)
    }

    // MediaAudioDevice Callback events (driven from sources)

    // metadata

    /* Test: Metadata changes from a Media Source should be forwarded onward if they are for the
     * active/preferred source.
     */
    @Test
    fun onMetadataChanged_forActiveDevice_browserServiceUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, metadata is set
        var metadataClassic = createMetadata("CLASSIC_TITLE_1a")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        val metadataClassic2 = createMetadata("CLASSIC2_TITLE_1")
        setMetadataForSource(mediaSourceClassic2, metadataClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Change metadata on (Active) Device 1 Media Source -> metadata updates
        metadataClassic = createMetadata("CLASSIC_TITLE_1b")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        classicCallback.onMetadataChanged(metadataClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)
        // assertThat(BluetoothMediaBrowserService.getMetadata()).isEqualTo(metadataClassic)
    }

    /* Test: Metadata changes from a Media Source should not be forwarded onward if they are not for
     * the active/preferred source.
     */
    @Test
    fun onMetadataChanged_forNonActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, metadata is set
        val metadataClassic = createMetadata("CLASSIC_TITLE_1")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var metadataClassic2 = createMetadata("CLASSIC2_TITLE_1a")
        setMetadataForSource(mediaSourceClassic2, metadataClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Change metadata on (Inactive) Device 2 Media Source -> Previous state remains
        metadataClassic2 = createMetadata("CLASSIC2_TITLE_1b")
        setMetadataForSource(mediaSourceClassic2, metadataClassic2)
        classicCallback2.onMetadataChanged(metadataClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)
        // assertThat(BluetoothMediaBrowserService.getMetadata()).isEqualTo(metadataClassic)
    }

    /* Test: Removing the active device when more than one device is connected does not set a new
     * active device. When in this state, events from a media source should not be forwarded on
     */
    @Test
    fun onMetadataChanged_withNoActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, metadata is set
        val metadataClassic = createMetadata("CLASSIC_TITLE_1a")
        setMetadataForSource(mediaSourceClassic, metadataClassic)
        addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var metadataClassic2 = createMetadata("CLASSIC2_TITLE_1")
        setMetadataForSource(mediaSourceClassic2, metadataClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getMetadata()).isEqualTo(metadataClassic)

        // Remove (Active) Device 1 -> No active device remains
        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getMetadata()).isNull()

        // Change metadata on (Inactive) Device 2 Media Source -> No changes to metadata state
        metadataClassic2 = createMetadata("CLASSIC2_TITLE_1b")
        setMetadataForSource(mediaSourceClassic2, metadataClassic2)
        classicCallback2.onMetadataChanged(metadataClassic2)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getMetadata()).isNull()
        // assertThat(BluetoothMediaBrowserService.getMetadata()).isNull()
    }

    // playback state

    /* Test: Playback state changes from a Media Source should be forwarded onward if they are for
     * the active/preferred source.
     */
    @Test
    fun onPlaybackStatusChanged_forActiveDevice_browserServiceUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, status is set
        var statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        val statusClassic2 = createPlaybackStatus(MediaSource.PlaybackState.STOPPED)
        setPlaybackStatusForSource(mediaSourceClassic2, statusClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Change metadata on (Active) Device 1 Media Source -> status updates
        statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        classicCallback.onPlaybackStatusChanged(statusClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)
        // assertThat(BluetoothMediaBrowserService.getPlaybackStatus()).isEqualTo(statusClassic)
    }

    /* Test: Playback state changes from a Media Source should not be forwarded onward if they are
     * not for the active/preferred source.
     */
    @Test
    fun onPlaybackStatusChanged_forNonActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, status is set
        val statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var statusClassic2 = createPlaybackStatus(MediaSource.PlaybackState.STOPPED)
        setPlaybackStatusForSource(mediaSourceClassic2, statusClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Change status on (Inactive) Device 2 Media Source -> No changes to metadata state
        statusClassic2 = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic2, statusClassic2)
        classicCallback2.onPlaybackStatusChanged(statusClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)
        // assertThat(BluetoothMediaBrowserService.getPlaybackStatus()).isEqualTo(statusClassic)
    }

    /* Test: Removing the active device when more than one device is connected does not set a new
     * active device. When in this state, events from a media source should not be forwarded on
     */
    @Test
    fun onPlaybackStatusChanged_withNoActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, status is set
        val statusClassic = createPlaybackStatus(MediaSource.PlaybackState.PAUSED)
        setPlaybackStatusForSource(mediaSourceClassic, statusClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var statusClassic2 = createPlaybackStatus(MediaSource.PlaybackState.STOPPED)
        setPlaybackStatusForSource(mediaSourceClassic2, statusClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getPlaybackStatus()).isEqualTo(statusClassic)

        // Remove (Active) Device 1 -> No active device remains
        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getPlaybackStatus()).isNull()

        // Change status on (Inactive) Device 2 Media Source -> No changes to metadata state
        statusClassic2 = createPlaybackStatus(MediaSource.PlaybackState.PLAYING)
        setPlaybackStatusForSource(mediaSourceClassic2, statusClassic2)
        classicCallback2.onPlaybackStatusChanged(statusClassic2)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getPlaybackStatus()).isNull()
        // assertThat(BluetoothMediaBrowserService.getPlaybackStatus()).isNull(); // error?
    }

    // now playing list

    /* Test: Now Playing queue changes from a Media Source should be forwarded onward if they are
     * for the active/preferred source.
     */
    @Test
    fun onNowPlayingListChanged_forActiveDevice_browserServiceUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, queue is set
        var queueClassic = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous queue remains
        val queueClassic2 = createNowPlayingList(7)
        setNowPlayingListForSource(mediaSourceClassic2, queueClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Change metadata on (Active) Device 1 Media Source -> queue updates
        queueClassic = createNowPlayingList(8)
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        classicCallback.onNowPlayingListChanged(queueClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)
        // assertThat(BluetoothMediaBrowserService.getNowPlayingList()).isEqualTo(queueClassic)
    }

    /* Test: Now Playing queue changes from a Media Source should not be forwarded on if they are
     * not for the active/preferred source.
     */
    @Test
    fun onNowPlayingListChanged_forNonActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, queue is set
        val queueClassic = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var queueClassic2 = createNowPlayingList(6)
        setNowPlayingListForSource(mediaSourceClassic2, queueClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Change queue on (Inactive) Device 2 Media Source -> No changes to metadata state
        queueClassic2 = createNowPlayingList(8)
        setNowPlayingListForSource(mediaSourceClassic2, queueClassic2)
        classicCallback2.onNowPlayingListChanged(queueClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)
        // assertThat(BluetoothMediaBrowserService.getNowPlayingList()).isEqualTo(queueClassic)
    }

    /* Test: Removing the active device when more than one device is connected does not set a new
     * active device. When in this state, events from a media source should not be forwarded on
     */
    @Test
    fun onNowPlayingListChanged_withNoActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Media Source -> Device is active, queue is set
        val queueClassic = createNowPlayingList(5)
        setNowPlayingListForSource(mediaSourceClassic, queueClassic)
        val classicCallback = addMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Add Device 2 Media Source -> Device is inactive. Previous state remains
        var queueClassic2 = createNowPlayingList(6)
        setNowPlayingListForSource(mediaSourceClassic2, queueClassic2)
        val classicCallback2 = addMediaSource(mediaSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getNowPlayingList()).isEqualTo(queueClassic)

        // Remove (Active) Device 1 -> No active device remains
        mediaAudioServer.unregisterMediaSource(mediaSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getNowPlayingList()).isEmpty()

        // Change queue on (Inactive) Device 2 Media Source -> No changes to metadata state
        queueClassic2 = createNowPlayingList(8)
        setNowPlayingListForSource(mediaSourceClassic2, queueClassic2)
        classicCallback2.onNowPlayingListChanged(queueClassic2)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getNowPlayingList()).isEmpty()
        // assertThat(BluetoothMediaBrowserService.getNowPlayingList()).isNull()
    }

    // browse node

    // TODO: Browsing is currently active device agnostic, but shouldn't be. These pass right
    // on through. We need to check the logic for things like request made then preferred source
    // changes while the UI is waiting on the response. The big impact is probably to the device
    // root contents changing

    /* Test: */
    @Test fun onBrowseNodeChanged_forActiveDevice_browserServiceUpdated() {}

    /* Test: */
    @Test fun onBrowseNodeChanged_forNonActiveDevice_browserServiceUpdated() {}

    /* Test: */
    @Test fun onBrowseNodeChanged_withNoActiveDevice_browserServiceUpdated() {}

    // audio stream state

    /* Test: Audio stream state changes from an Audio Source should be forwarded onward if they are
     * for the active/preferred source.
     */
    @Test
    fun onAudioStreamStateChanged_forActiveDevice_browserServiceUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Audio Source -> Device is active, state is set
        setStreamStateForSource(audioSourceClassic, AudioSource.StreamState.STREAMING)
        val classicCallback = addAudioSource(audioSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Add Device 2 Audio Source -> Device is inactive. Previous state remains
        setStreamStateForSource(audioSourceClassic2, AudioSource.StreamState.IDLE)
        val classicCallback2 = addAudioSource(audioSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Change metadata on (Active) Device 1 Audio Source -> state updates
        setStreamStateForSource(audioSourceClassic, AudioSource.StreamState.RELEASING)
        classicCallback.onStreamStateChanged(AudioSource.StreamState.RELEASING)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.RELEASING)
    }

    /* Test: Audio stream state changes from an Audio Source should not be forwarded onward if they
     * are not for the active/preferred source.
     */
    @Test
    fun onAudioStreamStateChanged_forNonActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Audio Source -> Device is active, state is set
        setStreamStateForSource(audioSourceClassic, AudioSource.StreamState.STREAMING)
        val classicCallback = addAudioSource(audioSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Add Device 2 Audio Source -> Device is inactive. Previous state remains
        setStreamStateForSource(audioSourceClassic2, AudioSource.StreamState.IDLE)
        val classicCallback2 = addAudioSource(audioSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Change metadata on (Inactive) Device 2 Audio Source -> state updates
        setStreamStateForSource(audioSourceClassic2, AudioSource.StreamState.RELEASING)
        classicCallback2.onStreamStateChanged(AudioSource.StreamState.RELEASING)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)
    }

    /* Test: Removing the active device when more than one device is connected does not set a new
     * active device. When in this state, events from an audio source should not be forwarded on
     */
    @Test
    fun onAudioStreamStateChanged_withNoActiveDevice_browserServiceNotUpdated() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)

        // Add Device 1 Audio Source -> Device is active, state is set
        setStreamStateForSource(audioSourceClassic, AudioSource.StreamState.STREAMING)
        val classicCallback = addAudioSource(audioSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Add Device 2 Audio Source -> Device is inactive. Previous state remains
        setStreamStateForSource(audioSourceClassic2, AudioSource.StreamState.IDLE)
        val classicCallback2 = addAudioSource(audioSourceClassic2)

        assertThat(mediaAudioServer.getActiveDevice()?.device).isEqualTo(device)
        assertThat(mediaAudioServer.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING)

        // Remove (Active) Device 1 -> No active device remains
        mediaAudioServer.unregisterAudioSource(audioSourceClassic)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getStreamState()).isNull()

        // Change metadata on (Inactive) Device 2 Audio Source -> no updates
        setStreamStateForSource(audioSourceClassic2, AudioSource.StreamState.RELEASING)
        classicCallback2.onStreamStateChanged(AudioSource.StreamState.RELEASING)

        assertThat(mediaAudioServer.getActiveDevice()).isNull()
        assertThat(mediaAudioServer.getStreamState()).isNull()
    }

    // Dump/toString

    /* Test: dump should produce some kind of output and should not crash */
    @Test
    fun dump_doesNotCrash() {
        mediaAudioServer = makeServer(mediaEnabled = true, audioEnabled = true)
        val sb: StringBuilder = StringBuilder()
        mediaAudioServer.dump(sb)
        assertThat(sb.toString()).isNotEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    private fun makeServer(mediaEnabled: Boolean, audioEnabled: Boolean): MediaAudioServer {
        ExtendedMockito.doReturn(mediaEnabled).`when` { AvrcpControllerService.isEnabled() }
        ExtendedMockito.doReturn(audioEnabled).`when` { A2dpSinkService.isEnabled() }
        return MediaAudioServer(mockContext)
    }

    private fun addMediaSource(source: MediaSource): MediaSource.Callback {
        mediaAudioServer.registerMediaSource(source)

        val callbackCaptor = argumentCaptor<MediaSource.Callback>()
        verify(source).registerCallback(callbackCaptor.capture())
        return callbackCaptor.firstValue
    }

    private fun addAudioSource(source: AudioSource): AudioSource.Callback {
        mediaAudioServer.registerAudioSource(source)

        val callbackCaptor = argumentCaptor<AudioSource.Callback>()
        verify(source).registerCallback(callbackCaptor.capture())
        return callbackCaptor.firstValue
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

    private fun setRootNodeForSource(source: MediaSource, mediaId: String) {
        val rootContents = MediaSource.BrowseNode(mediaId, createMetadata(mediaId), false, true)
        doReturn(rootContents).whenever(source).onGetRoot()
    }

    private fun setBrowseNodeForSource(
        source: MediaSource,
        mediaId: String,
        status: MediaSource.BrowseStatus,
    ) {
        val result = MediaSource.BrowseResult(emptyList(), status)
        setBrowseNodeForSource(source, mediaId, result)
    }

    private fun setBrowseNodeForSource(
        source: MediaSource,
        mediaId: String,
        contents: List<MediaSource.BrowseNode>,
    ) {
        val result = MediaSource.BrowseResult(contents, MediaSource.BrowseStatus.SUCCESS)
        setBrowseNodeForSource(source, mediaId, result)
    }

    private fun setBrowseNodeForSource(
        source: MediaSource,
        mediaId: String,
        result: MediaSource.BrowseResult,
    ) {
        doReturn(result).whenever(source).onBrowseRequest(MediaSource.BrowseRequest(mediaId))
    }

    private fun setStreamStateForSource(source: AudioSource, state: AudioSource.StreamState) {
        doReturn(state).whenever(source).getStreamState()
    }

    private fun mockFocusRequestResult(status: Int) {
        doReturn(status).whenever(mockAudioManager).requestAudioFocus(any())
    }

    // MediaPlayer.java directs setLooping() calls straight to a native function, which mockito
    // can't handle. This allows us to re-define setLooping as a non-native no-op, which mockito
    // can handle. If we don't do this, any calls to setLooping on a mock MediaPlayer cause the
    // calling function to exit immediately.
    private inner class TestMediaPlayer : MediaPlayer() {
        override fun setLooping(looping: Boolean) {
            /* do nothing */
        }
    }
}
