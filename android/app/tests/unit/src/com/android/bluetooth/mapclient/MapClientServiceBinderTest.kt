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

package com.android.bluetooth.mapclient

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.AttributionSource
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.verify

/** Test cases for [MapClientServiceBinder]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class MapClientServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: MapClientService

    private val device = getTestDevice(65)

    private lateinit var binder: MapClientServiceBinder

    @Before
    fun setUp() {
        binder = MapClientServiceBinder(service)
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
        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun sendMessage() {
        val contacts = arrayOf<Uri>()
        val message = "test_message"
        binder.sendMessage(device, contacts, message, null, null, source)
        verify(service).sendMessage(device, contacts, message, null, null)
    }

    @Test
    fun cleanup() {
        binder.cleanup()
    }
}
