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

package com.android.bluetooth.map

import android.app.AlarmManager
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [BluetoothMapService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class BluetoothMapServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val device = getTestDevice(32)
    private val context = InstrumentationRegistry.getInstrumentation().context

    private lateinit var service: BluetoothMapService

    @Before
    fun setUp() {
        doReturn(context.packageName).whenever(adapterService).packageName
        doReturn(context.packageManager).whenever(adapterService).packageManager
        doReturn(context.resources).whenever(adapterService).resources

        adapterService.mockGetSystemService<TelephonyManager>()
        adapterService.mockGetSystemService<AlarmManager>()

        service = BluetoothMapService(adapterService)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun getDevicesMatchingConnectionStates_whenNoDeviceIsConnected_returnsEmptyList() {
        doReturn(setOf(device)).whenever(adapterService).bondedDevices

        assertThat(service.getDevicesMatchingConnectionStates(intArrayOf(STATE_CONNECTED)))
            .isEmpty()
    }

    @Test
    fun getNextMasId_isInRange() {
        val masId = service.nextMasId
        assertThat(masId).isAtMost(0xff)
        assertThat(masId).isAtLeast(1)
    }

    @Test
    fun testDumpDoesNotCrash() {
        service.dump(StringBuilder())
    }
}
