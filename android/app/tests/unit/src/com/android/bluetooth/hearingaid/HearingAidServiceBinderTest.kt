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

package com.android.bluetooth.hearingaid

import android.bluetooth.BluetoothHearingAid
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

/** Test cases for [HearingAidServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HearingAidServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HearingAidService

    private val device = getTestDevice(0)

    private lateinit var binder: HearingAidServiceBinder

    @Before
    fun setUp() {
        doReturn(true).whenever(service).isAvailable
        binder = HearingAidServiceBinder(service)
    }

    @Test
    fun connect() {
        binder.connect(device, source)
        verify(service).connect(device)
    }

    @Test
    fun disconnect() {
        binder.disconnect(device, source)
        verify(service).disconnect(device)
    }

    @Test
    fun getConnectedDevices() {
        val connectedDevices = listOf(device)
        doReturn(connectedDevices).whenever(service).connectedDevices

        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED, STATE_DISCONNECTED)
        val devices = listOf(device)
        doReturn(devices).whenever(service).getDevicesMatchingConnectionStates(states)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        doReturn(STATE_CONNECTED).whenever(service).getConnectionState(device)

        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setActiveDevice() {
        binder.setActiveDevice(device, source)
        verify(service).setActiveDevice(device)
    }

    @Test
    fun removeActiveDevice() {
        binder.setActiveDevice(null, source)
        verify(service).removeActiveDevice(false)
    }

    @Test
    fun getActiveDevices() {
        val activeDevices = listOf(device)
        doReturn(activeDevices).whenever(service).getActiveDevices()

        binder.getActiveDevices(source)
        verify(service).getActiveDevices()
    }

    @Test
    fun setConnectionPolicy() {
        binder.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED, source)
        verify(service).setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED)
    }

    @Test
    fun getConnectionPolicy() {
        doReturn(CONNECTION_POLICY_FORBIDDEN).whenever(service).getConnectionPolicy(device)

        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun setVolume() {
        val volume = 50

        binder.setVolume(volume, source)
        verify(service).setVolume(volume)
    }

    @Test
    fun getHiSyncId() {
        val hiSyncId = 1234567890L
        doReturn(hiSyncId).whenever(service).getHiSyncId(device)

        binder.getHiSyncId(device, source)
        verify(service).getHiSyncId(device)
    }

    @Test
    fun getDeviceSide() {
        val side = BluetoothHearingAid.SIDE_LEFT
        doReturn(side).whenever(service).getCapabilities(device)

        binder.getDeviceSide(device, source)
        verify(service).getCapabilities(device)
    }

    @Test
    fun getDeviceMode() {
        val mode = BluetoothHearingAid.MODE_BINAURAL
        doReturn(mode shl 1).whenever(service).getCapabilities(device)

        binder.getDeviceMode(device, source)
        verify(service).getCapabilities(device)
    }

    @Test
    fun getAdvertisementServiceData() {
        val data = BluetoothHearingAid.AdvertisementServiceData(0, 0)
        doReturn(data).whenever(service).getAdvertisementServiceData(device)

        binder.getAdvertisementServiceData(device, source)
        verify(service).getAdvertisementServiceData(device)
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }
}
