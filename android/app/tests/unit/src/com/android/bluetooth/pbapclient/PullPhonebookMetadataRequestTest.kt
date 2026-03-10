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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.FakeObexServer
import com.android.obex.ApplicationParameter
import com.android.obex.ClientSession
import com.android.obex.HeaderSet
import com.android.obex.Operation
import com.android.obex.ResponseCodes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PullPhonebookMetadataRequestTest {

    private lateinit var server: FakePbapObexServer
    private lateinit var session: ClientSession
    private lateinit var request: PullPhonebookMetadataRequest

    @Before
    fun setUp() {
        server = FakePbapObexServer()
        session = server.getClientSession()

        val params =
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                PbapApplicationParameters.MAX_PHONEBOOK_SIZE,
                /* startOffset= */ 0,
            )
        request = PullPhonebookMetadataRequest(PHONEBOOK_NAME, params)
    }

    @Test
    fun getType_returnsTypeMetadataRequest() {
        assertThat(request.type).isEqualTo(PbapClientRequest.TYPE_PULL_PHONEBOOK_METADATA)
    }

    @Test
    fun getResponseCode_beforeExecutingRequest_returnsNegativeOne() {
        assertThat(request.responseCode).isEqualTo(-1)
    }

    @Test
    fun execute_sessionConnectedAndResponseOk_returnsMetadata() {
        session.connect(null)
        server.setSize(PHONEBOOK_SIZE)

        request.execute(session)

        assertThat(request.responseCode).isEqualTo(ResponseCodes.OBEX_HTTP_OK)
        assertThat(request.phonebook).isEqualTo(PHONEBOOK_NAME)

        val metadata = request.metadata
        assertThat(metadata.phonebook()).isEqualTo(PHONEBOOK_NAME)
        assertThat(metadata.size()).isEqualTo(200)
        assertThat(metadata.databaseIdentifier())
            .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER)
        assertThat(metadata.primaryVersionCounter())
            .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER)
        assertThat(metadata.secondaryVersionCounter())
            .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER)
    }

    @Test
    fun execute_sessionConnectedAndResponseBad_returnsEmptyMetadata() {
        session.connect(null)
        server.setResponseCode(ResponseCodes.OBEX_HTTP_BAD_REQUEST)
        request.execute(session)

        assertThat(request.responseCode).isEqualTo(ResponseCodes.OBEX_HTTP_BAD_REQUEST)
        assertThat(request.phonebook).isEqualTo(PHONEBOOK_NAME)

        val metadata = request.metadata
        assertThat(metadata.phonebook()).isEqualTo(PHONEBOOK_NAME)
        assertThat(metadata.size()).isEqualTo(PbapPhonebookMetadata.INVALID_SIZE)
        assertThat(metadata.databaseIdentifier())
            .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER)
        assertThat(metadata.primaryVersionCounter())
            .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER)
        assertThat(metadata.secondaryVersionCounter())
            .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER)
    }

    @Test
    fun execute_sessionNotConnected_throwsIOException() {
        assertThrows(IOException::class.java) { request.execute(session) }
        assertThat(request.responseCode).isEqualTo(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR)
    }

    @Test
    fun readResponseHeaders() {
        try {
            val headerSet = HeaderSet()
            request.readResponseHeaders(headerSet)
            assertThat(request.metadata.size()).isEqualTo(PbapPhonebookMetadata.INVALID_SIZE)
        } catch (e: Exception) {
            assertWithMessage("Exception should not happen.").fail()
        }
    }

    // *********************************************************************************************
    // * Fake PBAP Server
    // *********************************************************************************************

    private class FakePbapObexServer : FakeObexServer() {
        private var responseCode = ResponseCodes.OBEX_HTTP_OK
        private var size: Short = 0

        fun setResponseCode(responseCode: Int) {
            this.responseCode = responseCode
        }

        fun setSize(size: Short) {
            this.size = size
        }

        override fun onGet(op: Operation): Int {
            if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                return responseCode
            }

            val params = ApplicationParameter()
            params.addTriplet(
                PbapApplicationParameters.OAP_PHONEBOOK_SIZE,
                SIZE_BYTES,
                shortToByteArray(size),
            )

            val replyHeaders = HeaderSet()
            replyHeaders.setHeader(HeaderSet.APPLICATION_PARAMETER, params.header)
            return sendResponse(op, replyHeaders, null)
        }

        private fun shortToByteArray(s: Short) =
            ByteBuffer.allocate(2).apply { putShort(s) }.array()

        companion object {
            private const val SIZE_BYTES: Byte = 2
        }
    }

    companion object {
        private const val PHONEBOOK_NAME = "phonebook"
        private const val PHONEBOOK_SIZE: Short = 200
    }
}
