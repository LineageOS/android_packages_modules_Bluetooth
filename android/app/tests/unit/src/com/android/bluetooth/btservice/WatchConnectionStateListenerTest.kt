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
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils.MockitoRule
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetSystemService
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class WatchConnectionStateListenerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var packageManager: PackageManager

    private val device = getTestDevice(34)

    private lateinit var listener: WatchConnectionStateListener
    private lateinit var looper: TestLooper
    private lateinit var inOrder: InOrder

    @Before
    fun setUp() {
        inOrder = inOrder(adapterService)
        mockGetSystemService(adapterService, CompanionDeviceManager::class.java)
        doReturn(packageManager).whenever(adapterService).getPackageManager()
        looper = TestLooper()
        listener = WatchConnectionStateListener(adapterService, looper.getLooper())
    }

    @Test
    fun connectAWatch_whenNotConnected_triggerCallback() {
        doReturn(WEARABLE_WRIST_WATCH).whenever(adapterService).getRemoteClass(any())

        listener.onDeviceConnected(device, BluetoothDevice.TRANSPORT_BREDR)
        inOrder.verify(adapterService).updateWatchConnection(true)
    }

    @Test
    fun disconnectAWatch_whenConnected_triggerCallback() {
        connectAWatch_whenNotConnected_triggerCallback()

        listener.onDeviceDisconnected(device, BluetoothDevice.TRANSPORT_BREDR)
        inOrder.verify(adapterService).updateWatchConnection(false)
    }

    @Test
    fun leSpuriousConnection_whenConnected_notTriggerCallback() {
        connectAWatch_whenNotConnected_triggerCallback()

        listener.onDeviceConnected(device, BluetoothDevice.TRANSPORT_LE)
        listener.onDeviceDisconnected(device, BluetoothDevice.TRANSPORT_LE)
        inOrder.verify(adapterService, never()).updateWatchConnection(anyBoolean())
    }

    @Test
    fun leSpuriousConnection_whenNotConnected_triggerCallback() {
        doReturn(WEARABLE_WRIST_WATCH).whenever(adapterService).getRemoteClass(any())

        listener.onDeviceConnected(device, BluetoothDevice.TRANSPORT_LE)
        inOrder.verify(adapterService).updateWatchConnection(true)
        listener.onDeviceDisconnected(device, BluetoothDevice.TRANSPORT_LE)
        inOrder.verify(adapterService).updateWatchConnection(false)
    }
}
