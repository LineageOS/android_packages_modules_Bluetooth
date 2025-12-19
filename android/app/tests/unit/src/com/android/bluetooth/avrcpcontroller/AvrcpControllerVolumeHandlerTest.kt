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
    @get:Rule val mSetFlagsRule = SetFlagsRule()
    @get:Rule val mMockitoRule = MockitoRule()

    @Mock private lateinit var mAdapterService: AdapterService
    @Mock private lateinit var mAudioManager: AudioManager
    @Mock private lateinit var mPackageManager: PackageManager
    @Mock private lateinit var mCallback: AvrcpControllerVolumeHandler.Callback

    private val mDevice = getTestDevice(43)

    /** [makeVolumeHandler] must be called per test */
    private lateinit var mVolumeHandler: AvrcpControllerVolumeHandler
    // A temporary workaround in #makeVolumeHandler is done because the receiver is only registered
    // when Flags.avrcpControllerAbsVolChangedNotification() is true
    private var mBroadcastReceiver: BroadcastReceiver? = null
    // private lateinit var mBroadcastReceiver: BroadcastReceiver  // Use this when removing flag
    private val mLooper = TestLooper()

    @Before
    fun setUp() {
        doReturn(100).whenever(mAudioManager).getStreamMaxVolume(eq(AudioManager.STREAM_MUSIC))
        doReturn(25).whenever(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))

        doReturn(mPackageManager).whenever(mAdapterService).packageManager

        mockGetSystemService(mAdapterService, AudioManager::class.java, mAudioManager)
    }

    @After
    fun tearDown() {
        destroyAvrcpControllerVolumeHandler()
        assertThat(mLooper.nextMessage()).isNull()
    }

    // *********************************************************************************************
    // * Tests
    // *********************************************************************************************

    // getAbsoluteVolume

    /** Test #getAbsoluteVolume: fixed volume, not automotive = Loud */
    @Test
    fun testGetAbsoluteVolume_volumeIsFixed_getsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = true, isAutomotive = false)

        val absVol = mVolumeHandler.absoluteVolume
        assertThat(absVol).isEqualTo(127)
    }

    /** Test #getAbsoluteVolume: not fixed volume, automotive = Loud */
    @Test
    fun testGetAbsoluteVolume_isAutomotive_getsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val absVol = mVolumeHandler.absoluteVolume
        assertThat(absVol).isEqualTo(127)
    }

    /** Test #getAbsoluteVolume: not fixed volume, not automotive = Absolute */
    @Test
    fun testGetAbsoluteVolume_isAbsolute_doesNotGetAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        val absVol = mVolumeHandler.absoluteVolume
        assertThat(absVol).isEqualTo(31)
    }

    // setAbsoluteVolume

    /** Test #setAbsoluteVolume: fixed volume, not automotive = Loud */
    @Test
    fun testSetAbsoluteVolume_volumeIsFixed_setsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = true, isAutomotive = false)

        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(127)
        verifyNoSetStreamVolume()
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /** Test #setAbsoluteVolume: not fixed volume, automotive = Loud */
    @Test
    fun testSetAbsoluteVolume_isAutomotive_setsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(127)
        verifyNoSetStreamVolume()
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /** Test #setAbsoluteVolume: not fixed volume, not automotive = Absolute */
    @Test
    fun testSetAbsoluteVolume_twice_sameVol_isAbsolute_doesNotSetAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        var setLabel: Byte = 52
        var absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        clearInvocations(mAudioManager)

        // Setting absolute volume again with the same volume shouldn't change the stream volume
        setLabel++
        absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifyNoSetStreamVolume()
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    // Volume changed events

    /** Loud devices should not trigger the callback when events are received */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isAutomotive_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        // Receive event
        sendVolumeChangedEvent(39)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * Absolute volume devices should trigger the callback after volume changed events are received.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_isAbsolute_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Receive event
        sendVolumeChangedEvent(39)
        verify(mCallback).onAbsoluteVolumeChanged(49)
    }

    /**
     * When calling #setAbsoluteVolume, and then receiving two volume changed events for the same
     * volume that was set, absolute volume devices should not trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_afterSetAbsVol_twoEvents_sameVol_isAbsolute_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Set absolute volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event for the same volume that was set
        sendVolumeChangedEvent(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event for the same volume that was set, again
        sendVolumeChangedEvent(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * When calling #setAbsoluteVolume, and then receiving two volume changed events, with the
     * second one having a different volume, absolute volume devices should trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_afterSetAbsVol_twoEvents_secondDifferentVol_isAbsolute_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Set absolute volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event for the same volume that was set
        sendVolumeChangedEvent(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event for a different volume
        sendVolumeChangedEvent(39)
        verify(mCallback).onAbsoluteVolumeChanged(49)
    }

    /**
     * For the following sequence of events, absolute volume devices should trigger the callback
     * after both volume changed events:
     * * Call #setAbsoluteVolume x
     * * Receive volume changed event y
     * * Receive volume changed event back to x
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_afterSetAbsVol_twoEvents_backToOriginal_isAbsolute_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Set absolute volume x
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event y
        sendVolumeChangedEvent(39)
        verify(mCallback).onAbsoluteVolumeChanged(49)

        // Receive event x
        sendVolumeChangedEvent(15)
        // 19 instead of 20 because the inherent flooring of integer division makes the conversions
        // of local and absolute volume not inverses of each other
        verify(mCallback).onAbsoluteVolumeChanged(19)
    }

    /**
     * When receiving a volume changed event, and then calling #setAbsoluteVolume for the same
     * volume, absolute volume devices should not trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_beforeSetAbsVol_sameVol_isAbsolute_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Receive event
        sendVolumeChangedEvent(15)
        verify(mCallback).onAbsoluteVolumeChanged(19)

        clearInvocations(mCallback)

        // Set absolute volume for the same volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        // Setting absolute volume with the same volume as the previous event shouldn't change the
        // stream volume
        verifyNoSetStreamVolume()
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * When receiving a volume changed event, and then calling #setAbsoluteVolume for a different
     * volume, absolute volume devices should not trigger the callback.
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_beforeSetAbsVol_differentVol_isAbsolute_verifiesNoCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Receive event
        sendVolumeChangedEvent(39)
        verify(mCallback).onAbsoluteVolumeChanged(49)

        clearInvocations(mCallback)

        // Set absolute volume for a different volume
        val setLabel: Byte = 52
        val absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())
    }

    /**
     * For the following sequence of events, absolute volume devices should trigger the callback
     * after all volume changed events:
     * * Receive volume changed event x
     * * Call #setAbsoluteVolume y
     * * Call #setAbsoluteVolume z
     * * Receive volume changed event y
     * * Receive volume changed event z
     */
    @Test
    @EnableFlags(Flags.FLAG_AVRCP_CONTROLLER_ABS_VOL_CHANGED_NOTIFICATION)
    fun testEvent_interleaved_isAbsolute_verifiesCallback() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        // Receive event x
        sendVolumeChangedEvent(39)
        verify(mCallback).onAbsoluteVolumeChanged(49)

        clearInvocations(mCallback)

        // Set absolute volume y
        var setLabel: Byte = 52
        var absVol = setAbsoluteVolume(setLabel, 20)
        assertThat(absVol).isEqualTo(20)
        verifySetStreamVolume(15)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Set absolute volume z
        setLabel++
        absVol = setAbsoluteVolume(setLabel, 75)
        assertThat(absVol).isEqualTo(75)
        verifySetStreamVolume(59)
        verify(mCallback, never()).onAbsoluteVolumeChanged(any<Int>())

        // Receive event y
        sendVolumeChangedEvent(15)
        // 19 instead of 20 because the inherent flooring of integer division makes the conversions
        // of local and absolute volume not inverses of each other
        verify(mCallback).onAbsoluteVolumeChanged(19)

        // Receive event z
        sendVolumeChangedEvent(59)
        // 74 instead of 75 because the inherent flooring of integer division makes the conversions
        // of local and absolute volume not inverses of each other
        verify(mCallback).onAbsoluteVolumeChanged(74)
    }

    // *********************************************************************************************
    // * Test Utilities
    // *********************************************************************************************

    /** Create a volume handler to test */
    private fun makeVolumeHandler(isVolumeFixed: Boolean, isAutomotive: Boolean) {
        doReturn(isVolumeFixed).whenever(mAudioManager).isVolumeFixed

        // Absolute volume support (Utils.isAutomotive())
        doReturn(isAutomotive)
            .whenever(mPackageManager)
            .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

        mVolumeHandler =
            AvrcpControllerVolumeHandler(mAdapterService, mDevice, mCallback, mLooper.looper)
        mVolumeHandler.start()

        // Capture broadcast receiver
        val receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        // The temporary workaround below is done because the receiver is only registered when
        // Flags.avrcpControllerAbsVolChangedNotification() is true
        verify(mAdapterService, atLeast(0))
            .registerReceiver(receiverCaptor.capture(), any<IntentFilter>())
        val receivers = receiverCaptor.allValues
        if (!receivers.isEmpty()) mBroadcastReceiver = receivers.last()
        // Use this when removing flag
        // verify(mAdapterService, atLeastOnce())
        //     .registerReceiver(receiverCaptor.capture(), any<IntentFilter>())
        // mBroadcastReceiver = receiverCaptor.value
    }

    /** Destroy a volume handler you created to test */
    private fun destroyAvrcpControllerVolumeHandler() {
        mVolumeHandler.stop()
    }

    /** Call [AvrcpControllerVolumeHandler.setAbsoluteVolume] and drive the test looper. */
    private fun setAbsoluteVolume(setLabel: Byte, absVol: Int): Int {
        val absVolActual = mVolumeHandler.setAbsoluteVolume(absVol, setLabel.toInt())
        mLooper.dispatchAll()
        return absVolActual
    }

    /** Verify that [AudioManager.setStreamVolume] is called with the expected value. */
    private fun verifySetStreamVolume(localVol: Int) {
        verify(mAudioManager)
            .setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                eq(localVol),
                eq(AudioManager.FLAG_SHOW_UI),
            )
        doReturn(localVol).whenever(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))
    }

    /** Verify that [AudioManager.setStreamVolume] is not called. */
    private fun verifyNoSetStreamVolume() {
        verify(mAudioManager, never())
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
        doReturn(localVol).whenever(mAudioManager).getStreamVolume(eq(AudioManager.STREAM_MUSIC))
        mBroadcastReceiver!!.onReceive(mAdapterService, intent)
        mLooper.dispatchAll()
    }
}
