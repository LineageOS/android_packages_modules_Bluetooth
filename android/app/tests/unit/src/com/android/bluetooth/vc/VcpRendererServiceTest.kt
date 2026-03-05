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

package com.android.bluetooth.vcp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
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
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class VcpRendererServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: VcpRendererNativeInterface

    private lateinit var service: VcpRendererService
    private lateinit var looper: TestLooper
    private val device: BluetoothDevice = TestUtils.getTestDevice(0)

    @Before
    fun setUp() {
        looper = TestLooper()
        service = VcpRendererService(adapterService, looper.looper, nativeInterface)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun init() {
        // The service is initialized in setUp()
        verify(nativeInterface).init(any())
    }

    @Test
    fun cleanup() {
        service.cleanup()
        verify(nativeInterface).cleanup()
    }

    @Test
    fun updateVolumeState() {
        val volume = 100
        val muteState = MuteState.MUTED
        service.updateVolumeState(volume, muteState)
        verify(nativeInterface).updateVolumeState(volume, muteState)
    }

    @Test
    fun updateVolumeFlags() {
        val volumeSettingPersisted = VolumeSettingPersisted.USER_SET_VOLUME_SETTING
        service.updateVolumeFlags(volumeSettingPersisted)
        verify(nativeInterface).updateVolumeFlags(volumeSettingPersisted)
    }

    @Test
    fun onInitialized() {
        service.onInitialized()
    }

    @Test
    fun onConnectionStateChanged() {
        service.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
    }

    @Test
    fun onVolumeStateChangeRequest() {
        service.onVolumeStateChangeRequest(100, 1)
    }

    @Test
    fun dump() {
        val sb = StringBuilder()
        service.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }
}
