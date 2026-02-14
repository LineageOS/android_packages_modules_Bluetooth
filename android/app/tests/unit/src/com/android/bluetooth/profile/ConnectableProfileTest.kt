/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.profile

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.ProfileService.IProfileServiceBinder
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [ConnectableProfile]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ConnectableProfileTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var mockBinder: IProfileServiceBinder
    @Mock private lateinit var device: BluetoothDevice

    private lateinit var connectableProfile: TestConnectableProfile

    private inner class TestConnectableProfile(id: Int, adapterService: AdapterService) :
        ConnectableProfile(id, adapterService) {

        override fun initBinder(): IProfileServiceBinder {
            return mockBinder
        }

        // Nothing to do for test
        override fun cleanup() = Unit

        override fun connect(device: BluetoothDevice): Boolean {
            return false
        }

        override fun disconnect(device: BluetoothDevice): Boolean {
            return true
        }

        override fun getConnectionState(device: BluetoothDevice): Int {
            return BluetoothProfile.STATE_DISCONNECTED
        }

        override fun setConnectionPolicy(device: BluetoothDevice, connectionPolicy: Int): Boolean {
            return false
        }
    }

    @Before
    fun setUp() {
        connectableProfile = TestConnectableProfile(TEST_PROFILE_ID, adapterService)
    }

    @Test
    fun toString_returnsClassName() {
        assertThat(connectableProfile.toString()).isEqualTo("TestConnectableProfile")
    }

    @Test
    fun profileId_returnsCorrectId() {
        assertThat(connectableProfile.profileId).isEqualTo(TEST_PROFILE_ID)
    }

    @Test
    fun binder_returnsBinderFromInitBinder() {
        assertThat(connectableProfile.binder).hasValue(mockBinder)
    }

    @Test
    fun connect_returnsFalse() {
        assertThat(connectableProfile.connect(device)).isFalse()
    }

    @Test
    fun disconnect_returnsTrue() {
        assertThat(connectableProfile.disconnect(device)).isTrue()
    }

    @Test
    fun getConnectionState_returnsStateDisconnect() {
        assertThat(connectableProfile.getConnectionState(device))
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun getConnectionPolicy_callsDatabaseManager_returnsExpectedPolicy() {
        val expectedPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED
        doReturn(expectedPolicy)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, TEST_PROFILE_ID)

        assertThat(connectableProfile.getConnectionPolicy(device)).isEqualTo(expectedPolicy)
        verify(adapterService).getProfileConnectionPolicy(device, TEST_PROFILE_ID)
    }

    @Test
    fun setConnectionPolicy_returnsFalse() {
        val policyUnknown = BluetoothProfile.CONNECTION_POLICY_UNKNOWN
        assertThat(connectableProfile.setConnectionPolicy(device, policyUnknown)).isFalse()
    }

    @Test
    fun handleBondStateChanged_doesNotCrash() {
        connectableProfile.handleBondStateChanged(
            device,
            BluetoothDevice.BOND_NONE,
            BluetoothDevice.BOND_NONE,
        )
    }

    companion object {
        private const val TEST_PROFILE_ID = 99
    }
}
