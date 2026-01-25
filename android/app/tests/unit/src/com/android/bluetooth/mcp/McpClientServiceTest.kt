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

package com.android.bluetooth.mcp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class McpClientServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: McpClientNativeInterface

    private lateinit var service: McpClientService
    private lateinit var looper: TestLooper
    private val device = TestUtils.getTestDevice(0)

    @Before
    fun setUp() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any())

        looper = TestLooper()
        service = McpClientService(adapterService, looper.looper, nativeInterface)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun init() {
        // The service is initialized in setUp()
        verify(nativeInterface).init()
    }

    @Test
    fun cleanup() {
        service.cleanup()
        verify(nativeInterface).cleanup()
    }

    @Test
    fun connect() {
        // Initial state check
        assertThat(service.getConnectionState(device))
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTED)

        // Call service method
        assertThat(service.connect(device)).isTrue()

        // Dispatch messages in Looper (StateMachine must handle MESSAGE_CONNECT)
        looper.dispatchAll()

        // Verify nativeInterface called correct method
        verify(nativeInterface).connect(device)

        // Verify state change (StateMachine transitions to Connecting upon sending native command)
        assertThat(service.getConnectionState(device)).isEqualTo(BluetoothProfile.STATE_CONNECTING)
    }

    @Test
    fun disconnect() {
        // Connect first to create state machine and transition to connecting/connected
        service.connect(device)
        looper.dispatchAll()

        // Now disconnect
        assertThat(service.disconnect(device)).isTrue()
        looper.dispatchAll()

        // Verify native call
        verify(nativeInterface).disconnect(device)
    }

    @Test
    fun setConnectionPolicy_allowed_connects() {
        // Set policy to ALLOWED
        assertThat(service.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
            .isTrue()

        // Verify policy was stored in adapter service
        verify(adapterService)
            .setProfileConnectionPolicy(
                device,
                BluetoothProfile.MCP_CLIENT,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
            )

        // Policy ALLOWED should automatically trigger connect()
        looper.dispatchAll()
        verify(nativeInterface).connect(device)
    }

    @Test
    fun setConnectionPolicy_forbidden_disconnects() {
        // Start with a connected device
        service.connect(device)
        looper.dispatchAll()

        // Set policy to FORBIDDEN
        assertThat(
                service.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN)
            )
            .isTrue()

        // Verify policy was stored
        verify(adapterService)
            .setProfileConnectionPolicy(
                device,
                BluetoothProfile.MCP_CLIENT,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
            )

        // Policy FORBIDDEN should automatically trigger disconnect()
        looper.dispatchAll()
        verify(nativeInterface).disconnect(device)
    }

    @Test
    fun bondStateChanged_unbond_disconnects() {
        // Initial state: connected
        service.connect(device)
        looper.dispatchAll()

        // Simulate unbond
        service.handleBondStateChanged(
            device,
            BluetoothDevice.BOND_BONDED,
            BluetoothDevice.BOND_NONE,
        )
        looper.dispatchAll()

        // Service should disconnect the device
        verify(nativeInterface).disconnect(device)
    }

    @Test
    fun dump() {
        val sb = StringBuilder()
        service.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }
}
