/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class PbapClientObexTransportTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private BluetoothDevice mTestDevice;

    @Mock private PbapClientSocket mMockSocket;
    @Mock private InputStream mMockInputStream;
    @Mock private OutputStream mMockOutputStream;

    @Before
    public void setUp() throws IOException {
        mTestDevice = getTestDevice(1);

        doReturn(mMockInputStream).when(mMockSocket).getInputStream();
        doReturn(mMockOutputStream).when(mMockSocket).getOutputStream();
        doReturn(BluetoothSocket.TYPE_L2CAP).when(mMockSocket).getConnectionType();
        doReturn(255).when(mMockSocket).getMaxTransmitPacketSize();
        doReturn(255).when(mMockSocket).getMaxReceivePacketSize();
        doReturn(mTestDevice).when(mMockSocket).getRemoteDevice();
    }

    @Test
    public void testCloseTransport_socketIsClosed() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        transport.close();
        verify(mMockSocket).close();
    }

    @Test
    public void testOpenDataInputStream_containsSocketStream() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        DataInputStream in = transport.openDataInputStream();

        // DataInputStreams don't allow access to their underlying object, so we
        // can just do an operation and make sure the mock stream is used for
        // said operation
        doReturn(1).when(mMockInputStream).read();
        in.readBoolean();
        verify(mMockInputStream).read();
    }

    @Test
    public void testOpenDataOutputStream_containsSocketStream() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        DataOutputStream out = transport.openDataOutputStream();

        // DataOutputStreams don't allow access to their underlying object, so we
        // can just do an operation and make sure the mock stream is used for
        // said operation
        out.flush();
        verify(mMockOutputStream).flush();
    }

    @Test
    public void testOpenInputStream_containsSocketStream() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        InputStream in = transport.openInputStream();
        assertThat(mMockInputStream).isEqualTo(in);
    }

    @Test
    public void testOpenOutputStream_containsSocketStream() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        OutputStream out = transport.openOutputStream();
        assertThat(mMockOutputStream).isEqualTo(out);
    }

    @Test
    public void testConnect_doesNothing() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        transport.connect();
        verifyNoMoreInteractions(mMockSocket);
    }

    @Test
    public void testCreate_doesNothing() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        transport.create();
        verifyNoMoreInteractions(mMockSocket);
    }

    @Test
    public void testDisconnect_doesNothing() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        transport.disconnect();
        verifyNoMoreInteractions(mMockSocket);
    }

    @Test
    public void testListen_doesNothing() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        transport.listen();
        verifyNoMoreInteractions(mMockSocket);
    }

    @Test
    public void testIsConnected_returnsTrue() throws IOException {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.isConnected()).isTrue();
        verifyNoMoreInteractions(mMockSocket);
    }

    @Test
    public void testGetMaxTransmitPacketSize_transportL2cap_returns255() {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.getMaxTransmitPacketSize()).isEqualTo(255);
    }

    @Test
    public void testGetMaxTransmitPacketSize_transportRfcomm_returnsUnspecified() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).when(mMockSocket).getConnectionType();
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.getMaxTransmitPacketSize())
                .isEqualTo(PbapClientObexTransport.PACKET_SIZE_UNSPECIFIED);
    }

    @Test
    public void testGetMaxReceivePacketSize_transportL2cap_returns255() {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.getMaxReceivePacketSize()).isEqualTo(255);
    }

    @Test
    public void testGetMaxReceivePacketSize_transportRfcomm_returnsUnspecified() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).when(mMockSocket).getConnectionType();
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.getMaxReceivePacketSize())
                .isEqualTo(PbapClientObexTransport.PACKET_SIZE_UNSPECIFIED);
    }

    @Test
    public void testGetRemoteAddress_transportL2cap_returnsDeviceAddress() {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.getRemoteAddress()).isEqualTo(mTestDevice.getAddress());
    }

    @Test
    public void testGetRemoteAddress_transportRfcomm_returnsDeviceIdentityAddress() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).when(mMockSocket).getConnectionType();
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        // See "Flags.identityAddressNullIfNotKnown():"
        // Identity address won't be "known" by the stack for a test device, so it'll return null.
        // assertThat(transport.getRemoteAddress()).isNull();
        assertThat(transport.getRemoteAddress()).isEqualTo(mTestDevice.getAddress());
    }

    @Test
    public void testIsSrmSupported_transportL2cap_returnsTrue() {
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.isSrmSupported()).isTrue();
    }

    @Test
    public void testIsSrmSupported_transportRfcomm_returnsFalse() {
        doReturn(BluetoothSocket.TYPE_RFCOMM).when(mMockSocket).getConnectionType();
        PbapClientObexTransport transport = new PbapClientObexTransport(mMockSocket);
        assertThat(transport.isSrmSupported()).isFalse();
    }
}
