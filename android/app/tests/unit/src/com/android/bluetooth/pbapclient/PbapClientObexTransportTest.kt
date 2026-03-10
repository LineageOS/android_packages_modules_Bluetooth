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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/** Test cases for [PbapClientObexTransport]. */
@RunWith(AndroidJUnit4::class)
class PbapClientObexTransportTest {
    @get:Rule val mockitoRule = MockitoRule()

    private val device = getTestDevice(1)

    @Mock private lateinit var mockSocket: PbapClientSocket
    @Mock private lateinit var mockInputStream: InputStream
    @Mock private lateinit var mockOutputStream: OutputStream

    @Before
    fun setUp() {
        doReturn(mockInputStream).whenever(mockSocket).inputStream
        doReturn(mockOutputStream).whenever(mockSocket).outputStream
        doReturn(BluetoothSocket.TYPE_L2CAP).whenever(mockSocket).connectionType
        doReturn(255).whenever(mockSocket).maxTransmitPacketSize
        doReturn(255).whenever(mockSocket).maxReceivePacketSize
        doReturn(device).whenever(mockSocket).remoteDevice
    }

    @Test
    fun testCloseTransport_socketIsClosed() {
        val transport = PbapClientObexTransport(mockSocket)
        transport.close()
        verify(mockSocket).close()
    }

    @Test
    fun testOpenDataInputStream_containsSocketStream() {
        val transport = PbapClientObexTransport(mockSocket)
        val inputStream = transport.openDataInputStream()

        // DataInputStreams don't allow access to their underlying object, so we
        // can just do an operation and make sure the mock stream is used for
        // said operation
        doReturn(1).whenever(mockInputStream).read()
        inputStream.readBoolean()
        verify(mockInputStream).read()
    }

    @Test
    fun testOpenDataOutputStream_containsSocketStream() {
        val transport = PbapClientObexTransport(mockSocket)
        val out = transport.openDataOutputStream()

        // DataOutputStreams don't allow access to their underlying object, so we
        // can just do an operation and make sure the mock stream is used for
        // said operation
        out.flush()
        verify(mockOutputStream).flush()
    }

    @Test
    fun testOpenInputStream_containsSocketStream() {
        val transport = PbapClientObexTransport(mockSocket)
        val inputStream = transport.openInputStream()
        assertThat(inputStream).isEqualTo(mockInputStream)
    }

    @Test
    fun testOpenOutputStream_containsSocketStream() {
        val transport = PbapClientObexTransport(mockSocket)
        val out = transport.openOutputStream()
        assertThat(out).isEqualTo(mockOutputStream)
    }

    @Test
    fun testConnect_doesNothing() {
        val transport = PbapClientObexTransport(mockSocket)
        transport.connect()
        verifyNoMoreInteractions(mockSocket)
    }

    @Test
    fun testCreate_doesNothing() {
        val transport = PbapClientObexTransport(mockSocket)
        transport.create()
        verifyNoMoreInteractions(mockSocket)
    }

    @Test
    fun testDisconnect_doesNothing() {
        val transport = PbapClientObexTransport(mockSocket)
        transport.disconnect()
        verifyNoMoreInteractions(mockSocket)
    }

    @Test
    fun testListen_doesNothing() {
        val transport = PbapClientObexTransport(mockSocket)
        transport.listen()
        verifyNoMoreInteractions(mockSocket)
    }

    @Test
    fun testIsConnected_returnsTrue() {
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.isConnected).isTrue()
        verifyNoMoreInteractions(mockSocket)
    }

    @Test
    fun testGetMaxTransmitPacketSize_transportL2cap_returns255() {
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.maxTransmitPacketSize).isEqualTo(255)
    }

    @Test
    fun testGetMaxTransmitPacketSize_transportRfcomm_returnsUnspecified() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).whenever(mockSocket).connectionType
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.maxTransmitPacketSize)
            .isEqualTo(PbapClientObexTransport.PACKET_SIZE_UNSPECIFIED)
    }

    @Test
    fun testGetMaxReceivePacketSize_transportL2cap_returns255() {
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.maxReceivePacketSize).isEqualTo(255)
    }

    @Test
    fun testGetMaxReceivePacketSize_transportRfcomm_returnsUnspecified() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).whenever(mockSocket).connectionType
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.maxReceivePacketSize)
            .isEqualTo(PbapClientObexTransport.PACKET_SIZE_UNSPECIFIED)
    }

    @Test
    fun testIsSrmSupported_transportL2cap_returnsTrue() {
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.isSrmSupported).isTrue()
    }

    @Test
    fun testIsSrmSupported_transportRfcomm_returnsFalse() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).whenever(mockSocket).connectionType
        val transport = PbapClientObexTransport(mockSocket)
        assertThat(transport.isSrmSupported).isFalse()
    }
}
