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
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockBluetoothManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [SapService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class SapServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val device = getTestDevice(0)

    private lateinit var service: SapService

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        adapterService.mockBluetoothManager()
        service = SapService(adapterService)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    /** Test get connection policy for BluetoothDevice */
    @Test
    fun testGetConnectionPolicy() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, BluetoothProfile.SAP)
        assertThat(service.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_UNKNOWN)

        doReturn(CONNECTION_POLICY_FORBIDDEN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, BluetoothProfile.SAP)
        assertThat(service.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_FORBIDDEN)

        doReturn(CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, BluetoothProfile.SAP)

        assertThat(service.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_ALLOWED)
    }

    @Test
    fun testGetRemoteDevice() {
        assertThat(service.remoteDevice).isNull()
    }

    @Test
    fun testGetRemoteDeviceName() {
        assertThat(SapService.getRemoteDeviceName()).isNull()
    }

    @Test
    fun testReceiver_ConnectionAccessReplyIntent_shouldNotCrash() {
        val intent =
            Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY).apply {
                putExtra(
                    BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                    BluetoothDevice.REQUEST_TYPE_SIM_ACCESS,
                )
            }
        service.mSapReceiver.onReceive(null, intent)
    }
}
