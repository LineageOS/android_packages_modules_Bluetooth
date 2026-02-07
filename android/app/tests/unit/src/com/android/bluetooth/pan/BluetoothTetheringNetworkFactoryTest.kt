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

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [BluetoothTetheringNetworkFactory]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothTetheringNetworkFactoryTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var context: Context
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var panService: PanService

    private lateinit var bluetoothTetheringNetworkFactory: BluetoothTetheringNetworkFactory

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        context.mockGetSystemService<ConnectivityManager>(connectivityManager)
        bluetoothTetheringNetworkFactory =
            BluetoothTetheringNetworkFactory(context, Looper.myLooper(), panService)
    }

    @Test
    fun networkStartReverseTether() {
        val iface = "iface"
        bluetoothTetheringNetworkFactory.startReverseTether(iface)

        assertThat(bluetoothTetheringNetworkFactory.provider).isNotNull()
    }

    @Test
    fun networkStartReverseTetherStop() {
        val iface = "iface"
        bluetoothTetheringNetworkFactory.startReverseTether(iface)

        assertThat(bluetoothTetheringNetworkFactory.provider).isNotNull()

        val bluetoothDevice = getTestDevice(11)
        doReturn(listOf(bluetoothDevice)).whenever(panService).getConnectedDevices()

        bluetoothTetheringNetworkFactory.stopReverseTether()

        Mockito.verify(panService).getConnectedDevices()
        Mockito.verify(panService).disconnect(bluetoothDevice)
    }

    @Test
    fun networkStartReverseTetherEmptyIface() {
        val iface = ""
        bluetoothTetheringNetworkFactory.startReverseTether(iface)

        assertThat(bluetoothTetheringNetworkFactory.provider).isNull()
    }

    @Test
    fun networkStopEmptyIface() {
        bluetoothTetheringNetworkFactory.stopNetwork()
        bluetoothTetheringNetworkFactory.stopReverseTether()

        assertThat(bluetoothTetheringNetworkFactory.provider).isNull()
    }
}
