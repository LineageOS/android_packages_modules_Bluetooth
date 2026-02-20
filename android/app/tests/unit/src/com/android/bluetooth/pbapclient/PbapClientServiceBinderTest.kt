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

package com.android.bluetooth.pbapclient

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Test cases for [PbapClientServiceBinder]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class PbapClientServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: PbapClientService

    private val device = getTestDevice(1)

    private lateinit var binder: PbapClientServiceBinder

    @Before
    fun setUp() {
        binder = PbapClientServiceBinder(service)
    }

    @After
    fun tearDown() {
        binder.cleanup()
    }

    // *********************************************************************************************
    // * API Methods
    // *********************************************************************************************

    @Test
    fun connect() {
        binder.connect(device, source)
        verify(service).connect(eq(device))
    }

    @Test
    fun disconnect() {
        binder.disconnect(device, source)
        verify(service).disconnect(eq(device))
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
        verify(service).getDevicesMatchingConnectionStates(eq(states))
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(eq(device))
    }

    @Test
    fun setConnectionPolicy() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(eq(device), eq(connectionPolicy))
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(eq(device))
    }

    // *********************************************************************************************
    // * API Methods (Without service set, i.e. profile not up)
    // *********************************************************************************************

    @Test
    fun connect_afterCleanup_returnsFalse() {
        binder.cleanup()
        val result = binder.connect(device, source)
        verify(service, never()).connect(any())
        assertThat(result).isFalse()
    }

    @Test
    fun disconnect_afterCleanup_returnsFalse() {
        binder.cleanup()
        val result = binder.disconnect(device, source)
        verify(service, never()).disconnect(any())
        assertThat(result).isFalse()
    }

    @Test
    fun getConnectedDevices_afterCleanup_returnsEmptyList() {
        binder.cleanup()
        val devices = binder.getConnectedDevices(source)
        verify(service, never()).connectedDevices
        assertThat(devices).isEmpty()
    }

    @Test
    fun getDevicesMatchingConnectionStates_afterCleanup_returnsEmptyList() {
        binder.cleanup()
        val states = intArrayOf(STATE_CONNECTED)
        val devices = binder.getDevicesMatchingConnectionStates(states, source)
        verify(service, never()).getDevicesMatchingConnectionStates(any())
        assertThat(devices).isEmpty()
    }

    @Test
    fun getConnectionState_afterCleanup_returnsDisconnected() {
        binder.cleanup()
        val state = binder.getConnectionState(device, source)
        verify(service, never()).getConnectionState(any())
        assertThat(state).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun setConnectionPolicy_afterCleanup_returnsFalse() {
        binder.cleanup()
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        val result = binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service, never()).setConnectionPolicy(any(), any())
        assertThat(result).isFalse()
    }

    @Test
    fun getConnectionPolicy_afterCleanup_returnsUnknown() {
        binder.cleanup()
        val result = binder.getConnectionPolicy(device, source)
        verify(service, never()).getConnectionPolicy(any())
        assertThat(result).isEqualTo(CONNECTION_POLICY_UNKNOWN)
    }
}
