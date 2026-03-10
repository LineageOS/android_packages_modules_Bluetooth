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

package com.android.bluetooth.pbapclient

import android.bluetooth.BluetoothSocket
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.OutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify

/** Test cases for {@link PbapClientSocket}. */
@RunWith(AndroidJUnit4::class)
class PbapClientSocketTest {
    @get:Rule val mockitoRule = MockitoRule()

    private val device = getTestDevice(1)

    // This class is used to wrap the otherwise unmockable/untestable BluetoothSocket class. As such
    // its difficult to test that socket operations work in a unit test when we can't mock the
    // underlying socket framework. The best we can do test-wise is to test the injection framework
    @Mock private lateinit var injectedInput: InputStream
    @Mock private lateinit var injectedOutput: OutputStream

    @Before
    fun setUp() {
        PbapClientSocket.inject(injectedInput, injectedOutput)
    }

    @Test
    fun testCreateSocketWithInjection_usingL2cap() {
        val socket = PbapClientSocket.getL2capSocketForDevice(device, TEST_L2CAP_PSM)

        assertThat(socket.remoteDevice).isEqualTo(device)
        assertThat(socket.connectionType).isEqualTo(BluetoothSocket.TYPE_L2CAP)
        assertThat(socket.maxTransmitPacketSize).isEqualTo(255)
        assertThat(socket.maxReceivePacketSize).isEqualTo(255)
        assertThat(socket.inputStream).isEqualTo(injectedInput)
        assertThat(socket.outputStream).isEqualTo(injectedOutput)

        assertThat(socket.toString()).isNotNull()
        assertThat(socket.toString()).isNotEmpty()
    }

    @Test
    fun testCreateSocketWithInjection_usingRfcomm() {
        val socket = PbapClientSocket.getRfcommSocketForDevice(device, TEST_RFCOMM_CHANNEL_ID)

        assertThat(socket.remoteDevice).isEqualTo(device)
        assertThat(socket.connectionType).isEqualTo(BluetoothSocket.TYPE_RFCOMM)
        assertThat(socket.maxTransmitPacketSize).isEqualTo(255)
        assertThat(socket.maxReceivePacketSize).isEqualTo(255)
        assertThat(socket.inputStream).isEqualTo(injectedInput)
        assertThat(socket.outputStream).isEqualTo(injectedOutput)

        assertThat(socket.toString()).isNotNull()
        assertThat(socket.toString()).isNotEmpty()
    }

    @Test
    fun testCloseSocketWithInjection() {
        val socket = PbapClientSocket.getL2capSocketForDevice(device, TEST_L2CAP_PSM)
        socket.close()

        verify(injectedInput).close()
        verify(injectedOutput).close()
    }

    companion object {
        private const val TEST_L2CAP_PSM = 4098
        private const val TEST_RFCOMM_CHANNEL_ID = 3
    }
}
