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

package com.android.bluetooth.bas

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/** Test cases for [BatteryService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class BatteryServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val device = getTestDevice(78)

    private lateinit var looper: TestLooper
    private lateinit var service: BatteryService

    @Before
    fun setUp() {
        looper = TestLooper()

        doReturn(BOND_BONDED).whenever(adapterService).getBondState(any())

        service = BatteryService(adapterService, looper.looper)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun setConnectionPolicy() {
        assertThat(service.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
    }

    @Test
    fun getConnectionPolicy() {
        listOf(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_FORBIDDEN, CONNECTION_POLICY_ALLOWED)
            .forEach { policy ->
                doReturn(policy)
                    .whenever(adapterService)
                    .getProfileConnectionPolicy(any(), any<Int>())
                assertThat(service.getConnectionPolicy(device)).isEqualTo(policy)
            }
    }

    @Test
    fun canConnect_whenNotBonded_returnFalse() {
        val badPolicyValue = 1024
        val badBondState = 42
        val policies =
            listOf(
                CONNECTION_POLICY_UNKNOWN,
                CONNECTION_POLICY_FORBIDDEN,
                CONNECTION_POLICY_ALLOWED,
                badPolicyValue,
            )
        listOf(BOND_NONE, BOND_BONDING, badBondState).forEach { bondState ->
            policies.forEach { policy ->
                doReturn(bondState).whenever(adapterService).getBondState(any())
                doReturn(policy)
                    .whenever(adapterService)
                    .getProfileConnectionPolicy(any(), any<Int>())
                assertThat(service.canConnect(device)).isFalse()
            }
        }
    }

    @Test
    fun canConnect_whenBonded() {
        val badPolicyValue = 1024
        doReturn(BOND_BONDED).whenever(adapterService).getBondState(any())

        listOf(CONNECTION_POLICY_FORBIDDEN, badPolicyValue).forEach { policy ->
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any<Int>())
            assertThat(service.canConnect(device)).isFalse()
        }
        listOf(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED).forEach { policy ->
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any<Int>())
            assertThat(service.canConnect(device)).isEqualTo(true)
        }
    }

    @Test
    fun connectAndDump_doesNotCrash() {
        doReturn(CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any<Int>())

        doReturn(arrayOf(BluetoothUuid.BATTERY))
            .whenever(adapterService)
            .getRemoteUuids(any<BluetoothDevice>())

        assertThat(service.connect(device)).isTrue()
        service.dump(StringBuilder())
    }

    @Test
    fun connect_whenForbiddenPolicy_FailsToConnect() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any<Int>())

        assertThat(service.connect(device)).isFalse()
    }

    @Test
    fun getConnectionState_whenNoDevicesAreConnected_returnsDisconnectedState() {
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun getDevices_whenNoDevicesAreConnected_returnsEmptyList() {
        assertThat(service.getDevices()).isEmpty()
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        doReturn(setOf(device)).whenever(adapterService).bondedDevices
        val states = intArrayOf(STATE_DISCONNECTED)

        assertThat(service.getDevicesMatchingConnectionStates(states)).containsExactly(device)
    }
}
