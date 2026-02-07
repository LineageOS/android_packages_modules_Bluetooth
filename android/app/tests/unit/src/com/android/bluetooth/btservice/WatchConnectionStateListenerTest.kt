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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothClass.Device.WEARABLE_WRIST_WATCH
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.companion.CompanionDeviceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class WatchConnectionStateListenerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val device = getTestDevice(34)

    private lateinit var inOrder: InOrder
    private lateinit var looper: TestLooper
    private lateinit var listener: WatchConnectionStateListener

    @Before
    fun setUp() {
        adapterService.mockGetSystemService<CompanionDeviceManager>()
        adapterService.mockPackageManager()
        inOrder = inOrder(adapterService)
        looper = TestLooper()
        listener = WatchConnectionStateListener(adapterService, looper.looper)
    }

    @Test
    fun connectAWatch_whenNotConnected_triggerCallback() {
        doReturn(WEARABLE_WRIST_WATCH).whenever(adapterService).getRemoteClass(any())

        listener.onDeviceConnected(device, TRANSPORT_BREDR)
        inOrder.verify(adapterService).updateWatchConnection(true)
    }

    @Test
    fun disconnectAWatch_whenConnected_triggerCallback() {
        connectAWatch_whenNotConnected_triggerCallback()

        listener.onDeviceDisconnected(device, TRANSPORT_BREDR)
        inOrder.verify(adapterService).updateWatchConnection(false)
    }

    @Test
    fun leSpuriousConnection_whenConnected_notTriggerCallback() {
        connectAWatch_whenNotConnected_triggerCallback()

        listener.onDeviceConnected(device, TRANSPORT_LE)
        listener.onDeviceDisconnected(device, TRANSPORT_LE)
        inOrder.verify(adapterService, never()).updateWatchConnection(any<Boolean>())
    }

    @Test
    fun leSpuriousConnection_whenNotConnected_triggerCallback() {
        doReturn(WEARABLE_WRIST_WATCH).whenever(adapterService).getRemoteClass(any())

        listener.onDeviceConnected(device, TRANSPORT_LE)
        inOrder.verify(adapterService).updateWatchConnection(true)
        listener.onDeviceDisconnected(device, TRANSPORT_LE)
        inOrder.verify(adapterService).updateWatchConnection(false)
    }
}
