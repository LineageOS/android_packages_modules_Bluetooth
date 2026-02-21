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

package com.android.bluetooth.hid

import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.IBluetoothHidDeviceCallback
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [HidDeviceServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HidDeviceServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HidDeviceService

    private val device = getTestDevice(29)

    private lateinit var binder: HidDeviceServiceBinder

    @Before
    fun setUp() {
        doReturn(true).whenever(service).isAvailable
        binder = HidDeviceServiceBinder(service)
    }

    @Test
    fun cleanup() {
        binder.cleanup()
    }

    @Test
    fun registerApp() {
        val name = "test-name"
        val description = "test-description"
        val provider = "test-provider"
        val subclass: Byte = 1
        val descriptors = byteArrayOf(10)
        val sdp =
            BluetoothHidDeviceAppSdpSettings(name, description, provider, subclass, descriptors)

        val tokenRate = 800
        val tokenBucketSize = 9
        val peakBandwidth = 10
        val latency = 11250
        val delayVariation = BluetoothHidDeviceAppQosSettings.MAX
        val inQos =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                tokenRate,
                tokenBucketSize,
                peakBandwidth,
                latency,
                delayVariation,
            )
        val outQos =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                tokenRate,
                tokenBucketSize,
                peakBandwidth,
                latency,
                delayVariation,
            )
        val cb = mock<IBluetoothHidDeviceCallback>()

        binder.registerApp(sdp, inQos, outQos, cb, source)
        verify(service).registerApp(sdp, inQos, outQos, cb)
    }

    @Test
    fun unregisterApp() {
        binder.unregisterApp(source)
        verify(service).unregisterApp()
    }

    @Test
    fun sendReport() {
        val id = 100
        val data = byteArrayOf(0x00, 0x01)
        binder.sendReport(device, id, data, source)
        verify(service).sendReport(device, id, data)
    }

    @Test
    fun replyReport() {
        val type: Byte = 0
        val id: Byte = 100
        val data = byteArrayOf(0x00, 0x01)
        binder.replyReport(device, type, id, data, source)
        verify(service).replyReport(device, type, id, data)
    }

    @Test
    fun unplug() {
        binder.unplug(device, source)
        verify(service).unplug(device)
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
    fun reportError() {
        val error: Byte = -1
        binder.reportError(device, error, source)
        verify(service).reportError(device, error)
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).getDevicesMatchingConnectionStates(any<IntArray>())
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(BluetoothProfile.STATE_CONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getUserAppName() {
        binder.getUserAppName(source)
        verify(service).getUserAppName()
    }
}
