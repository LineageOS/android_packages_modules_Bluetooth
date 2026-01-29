/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
import androidx.test.filters.MediumTest
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.le_audio.LeAudioService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [McpService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class McpServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var mediaControlProfile: MediaControlProfile
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var leAudioService: LeAudioService

    private lateinit var mcpService: McpService

    @Before
    fun setUp() {
        doReturn(Optional.of(leAudioService)).whenever(adapterService).leAudioService
        mcpService = McpService(adapterService, mediaControlProfile)
        mcpService.isAvailable = true
    }

    @After
    fun tearDown() {
        mcpService.cleanup()
    }

    @Test
    fun testAuthorization() {
        val device0 = getTestDevice(0)
        val device1 = getTestDevice(1)

        mcpService.setDeviceAuthorized(device0, true)
        verify(mediaControlProfile).onDeviceAuthorizationSet(eq(device0))
        assertThat(mcpService.getDeviceAuthorization(device0))
            .isEqualTo(BluetoothDevice.ACCESS_ALLOWED)

        mcpService.setDeviceAuthorized(device1, false)
        verify(mediaControlProfile).onDeviceAuthorizationSet(eq(device1))
        assertThat(mcpService.getDeviceAuthorization(device1))
            .isEqualTo(BluetoothDevice.ACCESS_REJECTED)
    }

    @Test
    fun testDumpDoesNotCrash() {
        mcpService.dump(StringBuilder())
    }
}
