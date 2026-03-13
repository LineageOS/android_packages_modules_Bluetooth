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

package com.android.bluetooth.vcp

import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class VcpRendererNativeCallbackTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var service: VcpRendererService

    private lateinit var nativeCallback: VcpRendererNativeCallback
    private val device: BluetoothDevice = TestUtils.getTestDevice(0)
    private val deviceAddress: ByteArray = Util.getBytesFromAddress(device.address)

    @Before
    fun setUp() {
        doReturn(device).whenever(adapterService).getDeviceFromByte(any())

        // Stub service.post() to run immediately.
        // We use @Suppress("UNCHECKED_CAST") because generic function types are erased at runtime.
        doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val block = invocation.arguments[0] as (VcpRendererService) -> Unit
                block(service)
            }
            .whenever(service)
            .post(any())

        nativeCallback = VcpRendererNativeCallback(adapterService, service)
    }

    @Test
    fun onInitialized() {
        nativeCallback.onInitialized()
        verify(service).onInitialized()
    }

    @Test
    fun onConnectionStateChanged() {
        val state = 2 // Connected
        nativeCallback.onConnectionStateChanged(deviceAddress, state)
        verify(service).onConnectionStateChanged(device, state)
    }

    @Test
    fun onVolumeStateChangeRequest() {
        val volume = 100
        val muteState = 1 // Muted
        nativeCallback.onVolumeStateChangeRequest(volume, muteState)
        verify(service).onVolumeStateChangeRequest(volume, muteState)
    }
}
