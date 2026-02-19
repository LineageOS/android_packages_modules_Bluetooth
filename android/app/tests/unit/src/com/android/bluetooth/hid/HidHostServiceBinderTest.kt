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

import android.bluetooth.BluetoothDevice.TRANSPORT_AUTO
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.AttributionSource
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.verify

/** Test cases for [HidHostServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HidHostServiceBinderTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HidHostService

    private val device = getTestDevice(50)

    private lateinit var binder: HidHostServiceBinder

    @Before
    fun setUp() {
        binder = HidHostServiceBinder(service)
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
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).getDevicesMatchingConnectionStates(intArrayOf(STATE_CONNECTED))
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)
    }

    @Test
    fun setConnectionPolicy() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED
        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(service).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)
    }

    @Test
    fun setPreferredTransport() {
        val preferredTransport = TRANSPORT_AUTO
        binder.setPreferredTransport(device, preferredTransport, source)
        verify(service).setPreferredTransport(device, preferredTransport)
    }

    @Test
    fun getPreferredTransport() {
        binder.getPreferredTransport(device, source)
        verify(service).getPreferredTransport(device)
    }

    @Test
    fun getProtocolMode() {
        binder.getProtocolMode(device, source)
        verify(service).getProtocolMode(device)
    }

    @Test
    fun virtualUnplug() {
        binder.virtualUnplug(device, source)
        verify(service).virtualUnplug(device)
    }

    @Test
    fun setProtocolMode() {
        val protocolMode = 1
        binder.setProtocolMode(device, protocolMode, source)
        verify(service).setProtocolMode(device, protocolMode)
    }

    @Test
    fun getReport() {
        val reportType: Byte = 1
        val reportId: Byte = 2
        val bufferSize = 16
        binder.getReport(device, reportType, reportId, bufferSize, source)
        verify(service).getReport(device, reportType, reportId, bufferSize)
    }

    @Test
    fun setReport() {
        val reportType: Byte = 1
        val report = "test_report"
        binder.setReport(device, reportType, report, source)
        verify(service).setReport(device, reportType, report)
    }

    @Test
    fun sendData() {
        val report = "test_report"
        binder.sendData(device, report, source)
        verify(service).sendData(device, report)
    }

    @Test
    fun setIdleTime() {
        val idleTime: Byte = 1
        binder.setIdleTime(device, idleTime, source)
        verify(service).setIdleTime(device, idleTime)
    }

    @Test
    fun getIdleTime() {
        binder.getIdleTime(device, source)
        verify(service).getIdleTime(device)
    }

    @Test
    fun cleanUp() {
        binder.cleanup()
    }
}
