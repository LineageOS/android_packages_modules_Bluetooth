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

import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [AvrcpControllerStateMachine]. */
@RunWith(AndroidJUnit4::class)
class AvrcpControllerVolumeHandlerTest {
    @get:Rule val mMockitoRule = MockitoRule()

    @Mock private lateinit var mAdapterService: AdapterService
    @Mock private lateinit var mAudioManager: AudioManager
    @Mock private lateinit var mPackageManager: PackageManager

    private val mDevice = getTestDevice(43)

    /** [makeVolumeHandler] must be called per test */
    private lateinit var mVolumeHandler: AvrcpControllerVolumeHandler

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
        verifySetAbsVolume(setLabel, 20, 127)
        verify(mAudioManager, never())
            .setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                any<Int>(),
                eq(AudioManager.FLAG_SHOW_UI),
            )
    }

    /** Test #setAbsoluteVolume: not fixed volume, automotive = Loud */
    @Test
    fun testSetAbsoluteVolume_isAutomotive_setsAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = true)

        val setLabel: Byte = 52
        verifySetAbsVolume(setLabel, 20, 127)
        verify(mAudioManager, never())
            .setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                any<Int>(),
                eq(AudioManager.FLAG_SHOW_UI),
            )
    }

    /** Test #setAbsoluteVolume: not fixed volume, not automotive = Absolute */
    @Test
    fun testSetAbsoluteVolume_isAbsolute_doesNotSetAbsVolumeMax() {
        makeVolumeHandler(isVolumeFixed = false, isAutomotive = false)

        val setLabel: Byte = 52
        verifySetAbsVolume(setLabel, 20, 20)
        verify(mAudioManager)
            .setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(15), eq(AudioManager.FLAG_SHOW_UI))
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

        mVolumeHandler = AvrcpControllerVolumeHandler(mAdapterService, mDevice)
        mVolumeHandler.start()
    }

    /** Destroy a volume handler you created to test */
    private fun destroyAvrcpControllerVolumeHandler() {
        mVolumeHandler.stop()
    }

    private fun verifySetAbsVolume(setLabel: Byte, absVol: Int, absVolRsp: Int) {
        val absVolFromSet = mVolumeHandler.setAbsoluteVolume(absVol, setLabel.toInt())
        assertThat(absVolFromSet).isEqualTo(absVolRsp)
    }
}
