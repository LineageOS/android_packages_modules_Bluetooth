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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.METADATA_SOFTWARE_VERSION
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.SharedPreferences
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.CompanionManager.GATT_CONN_INTERVAL_MAX
import com.android.bluetooth.btservice.CompanionManager.GATT_CONN_INTERVAL_MIN
import com.android.bluetooth.btservice.CompanionManager.GATT_CONN_LATENCY
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockBluetoothManager
import com.android.bluetooth.mockGetRemoteDevice
import com.android.bluetooth.mockResources
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [CompanionManager]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class CompanionManagerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var sharedPreferences: SharedPreferences
    @Mock private lateinit var editor: SharedPreferences.Editor

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val device = getTestDevice(123)

    private lateinit var handlerThread: HandlerThread
    private lateinit var companionManager: CompanionManager

    @Before
    fun setUp() {
        adapterService.mockGetRemoteDevice(device)
        // Start handler thread for this test
        handlerThread = HandlerThread("CompanionManagerTestHandlerThread")
        handlerThread.start()
        // Mock the looper
        doReturn(handlerThread.looper).whenever(adapterService).mainLooper
        // Mock SharedPreferences
        doReturn(editor).whenever(sharedPreferences).edit()
        doReturn(sharedPreferences)
            .whenever(adapterService)
            .getSharedPreferences(eq(CompanionManager.COMPANION_INFO), eq(Context.MODE_PRIVATE))
        // Use the resources in the instrumentation instead of the mocked AdapterService
        adapterService.mockResources(context.resources)
        adapterService.mockBluetoothManager()

        companionManager = CompanionManager(adapterService)
    }

    @After
    fun tearDown() {
        handlerThread.quit()
    }

    @Test
    fun testLoadCompanionInfo_noCompanionDeviceInPrefs_checksMetadataOfBondedDevices() {
        val device1 = mock<BluetoothDevice>()
        val device2 = mock<BluetoothDevice>()
        doReturn(setOf(device1, device2)).whenever(adapterService).bondedDevices

        doReturn("")
            .whenever(sharedPreferences)
            .getString(eq(CompanionManager.COMPANION_DEVICE_KEY), any<String>())

        doReturn(CompanionManager.COMPANION_TYPE_NONE)
            .whenever(sharedPreferences)
            .getInt(eq(CompanionManager.COMPANION_TYPE_KEY), any<Int>())

        doReturn(null)
            .whenever(adapterService)
            .getMetadata(any<BluetoothDevice>(), eq(METADATA_SOFTWARE_VERSION))

        companionManager.loadCompanionInfo()

        verify(adapterService).getMetadata(eq(device1), eq(METADATA_SOFTWARE_VERSION))
        verify(adapterService).getMetadata(eq(device2), eq(METADATA_SOFTWARE_VERSION))
    }

    @Test
    fun testIsCompanionDevice() {
        var type = CompanionManager.COMPANION_TYPE_NONE
        loadCompanionInfoHelper(type)
        assertThat(companionManager.getCompanionType(device)).isEqualTo(type)

        type = CompanionManager.COMPANION_TYPE_PRIMARY
        loadCompanionInfoHelper(type)
        assertThat(companionManager.getCompanionType(device)).isEqualTo(type)

        type = CompanionManager.COMPANION_TYPE_SECONDARY
        loadCompanionInfoHelper(type)
        assertThat(companionManager.getCompanionType(device)).isEqualTo(type)
    }

    @Test
    fun testGetGattConnParameterPrimary() {
        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_PRIMARY)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_SECONDARY)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_NONE)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_DCK)
    }

    private fun loadCompanionInfoHelper(companionType: Int) {
        val address = device.address
        doReturn(address)
            .whenever(sharedPreferences)
            .getString(eq(CompanionManager.COMPANION_DEVICE_KEY), any<String>())
        doReturn(companionType)
            .whenever(sharedPreferences)
            .getInt(eq(CompanionManager.COMPANION_TYPE_KEY), any<Int>())
        companionManager.loadCompanionInfo()
    }

    private fun checkReasonableConnParameterHelper(priority: Int) {
        // Max/Min values from the Bluetooth spec Version 5.3 | Vol 4, Part E | 7.8.18
        val minInterval = 6 // 0x0006
        val maxInterval = 3200 // 0x0C80
        val minLatency = 0 // 0x0000
        val maxLatency = 499 // 0x01F3
        val minTimeout = 500 // 0x01F4
        val maxTimeout = 3200 // 0x0C80

        val min = companionManager.getGattConnParameters(device, GATT_CONN_INTERVAL_MIN, priority)
        val max = companionManager.getGattConnParameters(device, GATT_CONN_INTERVAL_MAX, priority)
        val latency = companionManager.getGattConnParameters(device, GATT_CONN_LATENCY, priority)
        val timeout = companionManager.getGattSupervisionTimeout(device)

        assertThat(max).isAtLeast(min)
        assertThat(max).isAtLeast(minInterval)
        assertThat(min).isAtLeast(minInterval)
        assertThat(max).isAtMost(maxInterval)
        assertThat(min).isAtMost(maxInterval)
        assertThat(latency).isAtLeast(minLatency)
        assertThat(latency).isAtMost(maxLatency)
        assertThat(timeout).isAtLeast(minTimeout)
        assertThat(timeout).isAtMost(maxTimeout)
    }
}
