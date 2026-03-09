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

import android.hardware.radio.sap.SapTransferProtocol
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.sap.SapMessage.CON_STATUS_OK
import com.android.bluetooth.sap.SapMessage.DISC_GRACEFUL
import com.android.bluetooth.sap.SapMessage.ID_CONNECT_REQ
import com.android.bluetooth.sap.SapMessage.ID_DISCONNECT_REQ
import com.android.bluetooth.sap.SapMessage.ID_POWER_SIM_OFF_REQ
import com.android.bluetooth.sap.SapMessage.ID_POWER_SIM_ON_REQ
import com.android.bluetooth.sap.SapMessage.ID_RESET_SIM_REQ
import com.android.bluetooth.sap.SapMessage.ID_SET_TRANSPORT_PROTOCOL_REQ
import com.android.bluetooth.sap.SapMessage.ID_TRANSFER_APDU_REQ
import com.android.bluetooth.sap.SapMessage.ID_TRANSFER_ATR_REQ
import com.android.bluetooth.sap.SapMessage.ID_TRANSFER_CARD_READER_STATUS_REQ
import com.android.bluetooth.sap.SapMessage.RESULT_OK
import com.android.bluetooth.sap.SapMessage.STATUS_CARD_INSERTED
import com.android.bluetooth.sap.SapMessage.TEST_MODE_ENABLE
import com.android.bluetooth.sap.SapMessage.TRANS_PROTO_T0
import com.android.bluetooth.sap.SapMessage.TRANS_PROTO_T1
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** Test cases for [SapMessage]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class SapMessageTest {

    private lateinit var message: SapMessage

    @Before
    fun setUp() {
        message = SapMessage(ID_CONNECT_REQ)
    }

    @Test
    fun settersAndGetters() {
        val msgType = ID_CONNECT_REQ
        val maxMsgSize = 512
        val connectionStatus = CON_STATUS_OK
        val resultCode = RESULT_OK
        val disconnectionType = DISC_GRACEFUL
        val cardReaderStatus = STATUS_CARD_INSERTED
        val statusChange = 1
        val transportProtocol = TRANS_PROTO_T0
        val apdu = byteArrayOf(0x01, 0x02)
        val apdu7816 = byteArrayOf(0x03, 0x04)
        val apduResp = byteArrayOf(0x05, 0x06)
        val atr = byteArrayOf(0x07, 0x08)
        val sendToRil = true
        val clearRilQueue = true
        val testMode = TEST_MODE_ENABLE

        message.msgType = msgType
        message.maxMsgSize = maxMsgSize
        message.connectionStatus = connectionStatus
        message.resultCode = resultCode
        message.disconnectionType = disconnectionType
        message.cardReaderStatus = cardReaderStatus
        message.statusChange = statusChange
        message.transportProtocol = transportProtocol
        message.apdu = apdu
        message.apdu7816 = apdu7816
        message.apduResp = apduResp
        message.atr = atr
        message.sendToRil = sendToRil
        message.clearRilQueue = clearRilQueue
        message.testMode = testMode

        assertThat(message.msgType).isEqualTo(msgType)
        assertThat(message.maxMsgSize).isEqualTo(maxMsgSize)
        assertThat(message.connectionStatus).isEqualTo(connectionStatus)
        assertThat(message.resultCode).isEqualTo(resultCode)
        assertThat(message.disconnectionType).isEqualTo(disconnectionType)
        assertThat(message.cardReaderStatus).isEqualTo(cardReaderStatus)
        assertThat(message.statusChange).isEqualTo(statusChange)
        assertThat(message.transportProtocol).isEqualTo(transportProtocol)
        assertThat(message.apdu).isEqualTo(apdu)
        assertThat(message.apdu7816).isEqualTo(apdu7816)
        assertThat(message.apduResp).isEqualTo(apduResp)
        assertThat(message.atr).isEqualTo(atr)
        assertThat(message.sendToRil).isEqualTo(sendToRil)
        assertThat(message.clearRilQueue).isEqualTo(clearRilQueue)
        assertThat(message.testMode).isEqualTo(testMode)
    }

    @Test
    fun getParamCount() {
        val paramCount = 3

        message.maxMsgSize = 512
        message.connectionStatus = CON_STATUS_OK
        message.resultCode = RESULT_OK

        assertThat(message.paramCount).isEqualTo(paramCount)
    }

    @Test
    fun getNumPendingRilMessages() {
        SapMessage.sOngoingRequests.put(/* rilSerial= */ 10000, ID_CONNECT_REQ)
        assertThat(SapMessage.getNumPendingRilMessages()).isEqualTo(1)

        SapMessage.resetPendingRilMessages()
        assertThat(SapMessage.getNumPendingRilMessages()).isEqualTo(0)
    }

    @Test
    fun writeAndRead() {
        val msgType = ID_CONNECT_REQ
        val maxMsgSize = 512
        val connectionStatus = CON_STATUS_OK
        val resultCode = RESULT_OK
        val disconnectionType = DISC_GRACEFUL
        val cardReaderStatus = STATUS_CARD_INSERTED
        val statusChange = 1
        val transportProtocol = TRANS_PROTO_T0
        val apdu = byteArrayOf(0x01, 0x02)
        val apdu7816 = byteArrayOf(0x03, 0x04)
        val apduResp = byteArrayOf(0x05, 0x06)
        val atr = byteArrayOf(0x07, 0x08)

        message.msgType = msgType
        message.maxMsgSize = maxMsgSize
        message.connectionStatus = connectionStatus
        message.resultCode = resultCode
        message.disconnectionType = disconnectionType
        message.cardReaderStatus = cardReaderStatus
        message.statusChange = statusChange
        message.transportProtocol = transportProtocol
        message.apdu = apdu
        message.apdu7816 = apdu7816
        message.apduResp = apduResp
        message.atr = atr

        val os = ByteArrayOutputStream()
        message.write(os)

        // Now, reconstruct the message from the written data.
        val data = os.toByteArray()
        val inputStream = ByteArrayInputStream(data)
        val msgTypeReadFromStream = inputStream.read()
        val msgFromInputStream = SapMessage.readMessage(msgTypeReadFromStream, inputStream)

        assertThat(msgFromInputStream.msgType).isEqualTo(msgType)
        assertThat(msgFromInputStream.maxMsgSize).isEqualTo(maxMsgSize)
        assertThat(msgFromInputStream.connectionStatus).isEqualTo(connectionStatus)
        assertThat(msgFromInputStream.resultCode).isEqualTo(resultCode)
        assertThat(msgFromInputStream.disconnectionType).isEqualTo(disconnectionType)
        assertThat(msgFromInputStream.cardReaderStatus).isEqualTo(cardReaderStatus)
        assertThat(msgFromInputStream.statusChange).isEqualTo(statusChange)
        assertThat(msgFromInputStream.transportProtocol).isEqualTo(transportProtocol)
        assertThat(msgFromInputStream.apdu).isEqualTo(apdu)
        assertThat(msgFromInputStream.apdu7816).isEqualTo(apdu7816)
        assertThat(msgFromInputStream.apduResp).isEqualTo(apduResp)
        assertThat(msgFromInputStream.atr).isEqualTo(atr)
    }

    // TODO: Add test for newInstance()
    // Note: MsgHeader throws a NoSuchMethodError when MsgHeader.getType() is called,
    //       which prevents writing tests for newInstance. Possibly a bug with protobuf?

    @Test
    fun send() {
        val maxMsgSize = 512
        val apdu = byteArrayOf(0x01, 0x02)
        val apdu7816 = byteArrayOf(0x03, 0x04)

        val sapProxy = mock<ISapRilReceiver>()
        message.clearRilQueue = true

        message.msgType = ID_CONNECT_REQ
        message.maxMsgSize = maxMsgSize
        message.send(sapProxy)
        verify(sapProxy).connectReq(any(), eq(maxMsgSize))
        clearInvocations(sapProxy)

        message.msgType = ID_DISCONNECT_REQ
        message.send(sapProxy)
        verify(sapProxy).disconnectReq(any())
        clearInvocations(sapProxy)

        message.msgType = ID_TRANSFER_APDU_REQ
        message.apdu = apdu
        message.send(sapProxy)
        verify(sapProxy).apduReq(any(), any(), any())
        clearInvocations(sapProxy)

        message.msgType = ID_TRANSFER_APDU_REQ
        message.apdu = null
        message.apdu7816 = apdu7816
        message.send(sapProxy)
        verify(sapProxy).apduReq(any(), any(), any())
        clearInvocations(sapProxy)

        message.msgType = ID_TRANSFER_APDU_REQ
        message.apdu = null
        message.apdu7816 = null
        assertThrows(IllegalArgumentException::class.java) { message.send(sapProxy) }

        message.msgType = ID_SET_TRANSPORT_PROTOCOL_REQ
        message.transportProtocol = TRANS_PROTO_T0
        message.send(sapProxy)
        verify(sapProxy).setTransferProtocolReq(any(), eq(SapTransferProtocol.T0))
        clearInvocations(sapProxy)

        message.msgType = ID_SET_TRANSPORT_PROTOCOL_REQ
        message.transportProtocol = TRANS_PROTO_T1
        message.send(sapProxy)
        verify(sapProxy).setTransferProtocolReq(any(), eq(SapTransferProtocol.T1))
        clearInvocations(sapProxy)

        message.msgType = ID_TRANSFER_ATR_REQ
        message.send(sapProxy)
        verify(sapProxy).transferAtrReq(any())
        clearInvocations(sapProxy)

        message.msgType = ID_POWER_SIM_OFF_REQ
        message.send(sapProxy)
        verify(sapProxy).powerReq(any(), eq(false))
        clearInvocations(sapProxy)

        message.msgType = ID_POWER_SIM_ON_REQ
        message.send(sapProxy)
        verify(sapProxy).powerReq(any(), eq(true))
        clearInvocations(sapProxy)

        message.msgType = ID_RESET_SIM_REQ
        message.send(sapProxy)
        verify(sapProxy).resetSimReq(any())
        clearInvocations(sapProxy)

        message.msgType = ID_TRANSFER_CARD_READER_STATUS_REQ
        message.send(sapProxy)
        verify(sapProxy).transferCardReaderStatusReq(any())
        clearInvocations(sapProxy)

        message.msgType = -1000
        assertThrows(IllegalArgumentException::class.java) { message.send(sapProxy) }
    }
}
