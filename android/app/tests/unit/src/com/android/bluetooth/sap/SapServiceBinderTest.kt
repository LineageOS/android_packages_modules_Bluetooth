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

package com.android.bluetooth.sap

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [SapServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class SapServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: SapService

    private lateinit var binder: SapServiceBinder

    @Before
    fun setUp() {
        doReturn(true).whenever(service).isAvailable
        binder = SapServiceBinder(service)
    }

    @Test
    fun getState() {
        binder.getState(source)
        verify(service).state
    }

    @Test
    fun getClient() {
        binder.getClient(source)
        // times(2) due to the Log
        verify(service, times(2)).remoteDevice
    }

    @Test
    fun isConnected() {
        val device = mock<BluetoothDevice>()
        binder.isConnected(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun disconnect() {
        val device = mock<BluetoothDevice>()
        binder.disconnect(device, source)
        verify(service).disconnect(device)
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED, STATE_DISCONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        val device = mock<BluetoothDevice>()
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy() {
        val device = mock<BluetoothDevice>()
        val connectionPolicy = 1
        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        val device = mock<BluetoothDevice>()
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun cleanup() {
        binder.cleanup()
    }
}
