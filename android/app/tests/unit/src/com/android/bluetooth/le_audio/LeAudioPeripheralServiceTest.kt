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

import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@EnableFlags(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
@Suppress("DEPRECATION")
@SmallTest
@RunWith(AndroidJUnit4ClassRunner::class)
class LeAudioPeripheralServiceTest {

    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: LeAudioPeripheralNativeInterface
    @Mock private lateinit var audioManager: AudioManager

    private lateinit var service: LeAudioPeripheralService
    private lateinit var testLooper: TestLooper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testLooper = TestLooper()

        // Use the official TestUtils to mock getSystemService on the AdapterService mock.
        // This is the key to solving the ContextWrapper delegation issue for ProfileServices.
        TestUtils.mockGetSystemService(adapterService, AudioManager::class.java, audioManager)

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
}
