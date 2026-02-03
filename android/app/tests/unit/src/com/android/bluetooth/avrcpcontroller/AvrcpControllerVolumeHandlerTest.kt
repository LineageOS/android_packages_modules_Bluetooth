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

package com.android.bluetooth.avrcpcontroller

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [AvrcpControllerStateMachine]. */
@RunWith(AndroidJUnit4::class)
class AvrcpControllerVolumeHandlerTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var audioManager: AudioManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var callback: AvrcpControllerVolumeHandler.Callback

    private val device = getTestDevice(43)

    /** [makeVolumeHandler] must be called per test */
    private lateinit var volumeHandler: AvrcpControllerVolumeHandler
    // A temporary workaround in #makeVolumeHandler is done because the receiver is only registered
    // when Flags.avrcpControllerAbsVolChangedNotification() is true
    private var receiver: BroadcastReceiver? = null
    // private lateinit var receiver: BroadcastReceiver  // Use this when removing flag
    private val looper = TestLooper()

    @Before
    fun setUp() {
        doReturn(100).whenever(audioManager).getStreamMaxVolume(eq(AudioManager.STREAM_MUSIC))
        doReturn(25).whenever(audioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))

        doReturn(packageManager).whenever(adapterService).packageManager

        mockGetSystemService(adapterService, AudioManager::class.java, audioManager)
    }

    @After
    fun tearDown() {
        destroyAvrcpControllerVolumeHandler()
        assertThat(looper.nextMessage()).isNull()
    }

    // *********************************************************************************************
    // * Tests
    // *********************************************************************************************

    // getVolumeStrategy

    /** Test #getVolumeStrategy: fixed volume, automotive = Loud */
    @Test
    fun testGetVolumeStrategy_isVolumeFixed_isAutomotive_getsStrategyLoud() {
        makeVolumeHandler(isVolumeFixed = true, isAutomotive = true)

        val strategy = volumeHandler.volumeStrategy
        assertThat(strategy).isEqualTo(AvrcpControllerVolumeHandler.STRATEGY_LOUD)
    }

    /** Test #getVolumeStrategy: fixed volume, not automotive = Loud */
    @Test
    fun testGetVolumeStrategy_isVolumeFixed_getsStrategyLoud() {
        makeVolumeHandler(isVolumeFixed = true, isAutomotive = false)

        val strategy = volumeHandler.volumeStrategy
        assertThat(strategy).isEqualTo(AvrcpControllerVolumeHandler.STRATEGY_LOUD)
    }

    /** Test #getVolumeStrategy: not fixed volume, automotive = Loud */
    @Test
    fun testGetVolumeStrategy_isAutomotive_getsStrategyLoud() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val strategy = volumeHandler.volumeStrategy
        assertThat(strategy).isEqualTo(AvrcpControllerVolumeHandler.STRATEGY_LOUD)
    }

    /** Test #getVolumeStrategy: not fixed volume, not automotive = Absolute */
    @Test
    fun testGetVolumeStrategy_notVolumeFixed_notAutomotive_getsStrategyAbsolute() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        val strategy = volumeHandler.volumeStrategy
        assertThat(strategy).isEqualTo(AvrcpControllerVolumeHandler.STRATEGY_ABSOLUTE)
    }

    // getAbsoluteVolume

    /** Test #getAbsoluteVolume: Strategy Loud */
    @Test
    fun testGetAbsoluteVolume_isStrategyLoud_getsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val absVol = volumeHandler.absoluteVolume
        assertThat(absVol).isEqualTo(127)
    }

    /** Test #getAbsoluteVolume: Strategy Absolute */
    @Test
    fun testGetAbsoluteVolume_isStrategyAbsolute_doesNotGetAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        val absVol = volumeHandler.absoluteVolume
        assertThat(absVol).isEqualTo(31)
    }

    // setAbsoluteVolume

    /** Test #setAbsoluteVolume: Strategy Loud */
    @Test
    fun testSetAbsoluteVolume_isStrategyLoud_returnsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(127)
        // Loud devices should never set stream volume
        verifyNoSetStreamVolume()
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /** Test #setAbsoluteVolume: Strategy Absolute */
    @Test
    fun testSetAbsoluteVolume_isStrategyAbsolute_doesNotReturnAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /** Test #setAbsoluteVolume: Strategy Absolute */
    @Test
    fun testSetAbsoluteVolume_isStrategyAbsolute_currentVol_doesNotSetStreamVolume() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Absolute volume 32 -> Local volume 25
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 32)
        assertThat(absVol).isEqualTo(32)
        // Setting absolute volume to match the current stream volume shouldn't change the stream
        // volume
        verifyNoSetStreamVolume()
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    // Volume changed events

    /** Loud devices should not trigger the callback after a volume changed event */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyLoud_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        // Volume changed event
        sendVolumeChangedEvent(39)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /** Absolute volume devices should trigger the callback after a volume changed event */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Volume changed event
        sendVolumeChangedEvent(39)
        verify(callback).onAbsoluteVolumeChanged(49)
    }

    /**
     * If a volume changed event matches the current stream volume, absolute volume devices should
     * not trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_currentVol_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Volume changed event that matches the current stream volume
        sendVolumeChangedEvent(25)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * If a volume changed event occurs after setting absolute volume, for a different volume than
     * was set, absolute volume devices should trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_afterSetAbsVol_differentVol_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Set absolute volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Volume changed event for a different volume than was set
        sendVolumeChangedEvent(39)
        verify(callback).onAbsoluteVolumeChanged(49)
    }

    /**
     * If a volume changed event occurs after setting absolute volume, for the same volume that was
     * set, absolute volume devices should not trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_afterSetAbsVol_sameVol_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Set absolute volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Volume changed event for the same volume that was set
        sendVolumeChangedEvent(15)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * When setting absolute volume after a volume changed event occurs, to a different volume than
     * the event, absolute volume devices should not change the stream volume.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_beforeSetAbsVol_differentVol_setsStreamVolume() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Volume changed event
        sendVolumeChangedEvent(39)
        verify(callback).onAbsoluteVolumeChanged(49)

        clearInvocations(callback)

        // Set absolute volume to a different volume than the event
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * When setting absolute volume after a volume changed event occurs, to the same volume as the
     * event, absolute volume devices should not change the stream volume.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isStrategyAbsolute_beforeSetAbsVol_sameVol_doesNotSetStreamVolume() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Volume changed event
        sendVolumeChangedEvent(15)
        verify(callback).onAbsoluteVolumeChanged(19)

        clearInvocations(callback)

        // Set absolute volume to the same volume as the event
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        // Setting absolute volume with the same volume as the previous event shouldn't change the
        // stream volume
        verifyNoSetStreamVolume()
        verify(callback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    // *********************************************************************************************
    // * Test Utilities
    // *********************************************************************************************

    /** Create a volume handler to test */
    private fun makeVolumeHandler(isVolumeFixed: Boolean, isAutomotive: Boolean) {
        doReturn(isVolumeFixed).whenever(audioManager).isVolumeFixed

        // Absolute volume support (Utils.isAutomotive())
        doReturn(isAutomotive)
            .whenever(packageManager)
            .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

        volumeHandler =
            AvrcpControllerVolumeHandler(adapterService, device, callback, looper.looper)
        volumeHandler.start()

        // Capture broadcast receiver
        val receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        // The temporary workaround below is done because the receiver is only registered when
        // Flags.avrcpControllerAbsVolChangedNotification() is true
        verify(adapterService, atLeast(0))
            .registerReceiver(receiverCaptor.capture(), any<IntentFilter>())
        val receivers = receiverCaptor.allValues
        if (!receivers.isEmpty()) receiver = receivers.last()
        // Use this when removing flag
        // verify(adapterService, atLeastOnce())
        //     .registerReceiver(receiverCaptor.capture(), any<IntentFilter>())
        // receiver = receiverCaptor.value
    }

    /** Destroy a volume handler you created to test */
    private fun destroyAvrcpControllerVolumeHandler() {
        volumeHandler.stop()
    }

    /** Call [AvrcpControllerVolumeHandler.setAbsoluteVolume] and drive the test looper. */
    private fun setAbsoluteVolume(setLabel: Byte, absVol: Int): Int {
        val absVolActual = volumeHandler.setAbsoluteVolume(absVol, setLabel.toInt())
        looper.dispatchAll()
        return absVolActual
    }

    /** Verify that [AudioManager.setStreamVolume] is called with the expected value. */
    private fun verifySetStreamVolume(localVol: Int) {
        verify(audioManager)
            .setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                eq(localVol),
                eq(AudioManager.FLAG_SHOW_UI),
            )
        doReturn(localVol).whenever(audioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))
    }

    /** Verify that [AudioManager.setStreamVolume] is not called. */
    private fun verifyNoSetStreamVolume() {
        verify(audioManager, never())
            .setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                any<Int>(),
                eq(AudioManager.FLAG_SHOW_UI),
            )
    }

    /**
     * Send a volume changed event for volume level: [localVol], in the device's domain, not the
     * absolute volume domain.
     *
     * Only use with [Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION].
     */
    private fun sendVolumeChangedEvent(localVol: Int) {
        val intent = Intent(AudioManager.ACTION_VOLUME_CHANGED)
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, localVol)
        doReturn(localVol).whenever(audioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))
        receiver!!.onReceive(adapterService, intent)
        looper.dispatchAll()
    }
}
