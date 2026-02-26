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

package com.android.bluetooth.a2dpsink

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.verify

/** Test cases for [A2dpSinkServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class A2dpSinkServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: A2dpSinkService

    private lateinit var binder: A2dpSinkServiceBinder

    @Before
    fun setUp() {
        binder = A2dpSinkServiceBinder(service)
    }

    @After
    fun cleanup() {
        binder.cleanup()
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
        val device = getTestDevice(0)
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy() {
        val device = getTestDevice(0)
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        val device = getTestDevice(0)
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun isA2dpPlaying() {
        val device = getTestDevice(0)
        binder.isA2dpPlaying(device, source)
        verify(service).isA2dpPlaying(device)
    }
}
