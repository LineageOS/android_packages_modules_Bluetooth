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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothDevice
import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.mockGetSystemService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@EnableFlags(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
@Suppress("DEPRECATION")
@SmallTest
@RunWith(AndroidJUnit4ClassRunner::class)
class LeAudioPeripheralServiceTest {

    @get:Rule val setFlagsRule = SetFlagsRule()

    private var adapterService: AdapterService = mock()
    private var nativeInterface: LeAudioPeripheralNativeInterface = mock()
    private var audioManager: AudioManager = mock()
    private val mockPolicyManager: PeripheralPolicyManager = mock()

    private lateinit var service: LeAudioPeripheralService
    private lateinit var testLooper: TestLooper

    private val device1: BluetoothDevice = getTestDevice(0)
    private val device2: BluetoothDevice = getTestDevice(1)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testLooper = TestLooper()

        // Use the official TestUtil to mock getSystemService on the AdapterService mock.
        // This is the key to solving the ContextWrapper delegation issue for ProfileServices.
        adapterService.mockGetSystemService(audioManager)

        // Now, when the service is created, it will wrap the adapterService. When AudioProxy
        // calls getSystemService on the service, the call will be correctly delegated to our
        // mocked AdapterService, which will now return the mock AudioManager.
        service = LeAudioPeripheralService(adapterService, testLooper.looper, nativeInterface)
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun testCreationAndCleanup() {
        Assert.assertNotNull(service)
        // Verify native interface was initialized by the service's init {} block
        verify(nativeInterface).init()

        service.cleanup()
        verify(nativeInterface).cleanup()
    }

    @Test
    fun testGetActiveDevices_returnsDevicesFromPolicyManager() {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()

        doReturn(device1).whenever(mockPolicyManager).activeSinkDevice
        doReturn(device2).whenever(mockPolicyManager).activeSourceDevice

        service.setPolicyManagerForTesting(mockPolicyManager)

        val result = service.getActiveDevices()

        assertThat(service.getActiveDevices()).containsExactly(device1, device2)
    }

    @Test
    fun testGetActiveDevices_filtersNullDevices() {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()

        doReturn(device1).whenever(mockPolicyManager).activeSinkDevice
        doReturn(null).whenever(mockPolicyManager).activeSourceDevice

        service.setPolicyManagerForTesting(mockPolicyManager)

        val result = service.getActiveDevices()

        assertThat(service.getActiveDevices()).containsExactly(device1)
    }

    @Test
    fun testGetActiveDevices_returnsEmptyListWhenNoDevicesActive() {
        doReturn(null).whenever(mockPolicyManager).activeSinkDevice
        doReturn(null).whenever(mockPolicyManager).activeSourceDevice

        service.setPolicyManagerForTesting(mockPolicyManager)

        val result = service.getActiveDevices()

        assertThat(service.getActiveDevices()).isEmpty()
    }
}
