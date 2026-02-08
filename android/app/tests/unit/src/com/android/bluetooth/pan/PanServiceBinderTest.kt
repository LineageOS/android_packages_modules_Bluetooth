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

package com.android.bluetooth.pan

import android.bluetooth.BluetoothProfile
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
import org.mockito.Mockito.verify

/** Test cases for [PanServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PanServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: PanService

    private val device = getTestDevice(64)

    private lateinit var binder: PanServiceBinder

    @Before
    fun setUp() {
        binder = PanServiceBinder(service)
    }

    @Test
    fun connect_callsServiceMethod() {
        binder.connect(device, source)
        verify(service).connect(device)
    }

    @Test
    fun disconnect_callsServiceMethod() {
        binder.disconnect(device, source)
        verify(service).disconnect(device)
    }

    @Test
    fun getConnectedDevices_callsServiceMethod() {
        binder.getConnectedDevices(source)
        verify(service).getConnectedDevices()
    }

    @Test
    fun getDevicesMatchingConnectionStates_callsServiceMethod() {
        val states = intArrayOf(BluetoothProfile.STATE_CONNECTED)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState_callsServiceMethod() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy_callsServiceMethod() {
        val connectionPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED

        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun isTetheringOn_callsServiceMethod() {
        binder.isTetheringOn(source)
        verify(service).isTetheringOn
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }
}
