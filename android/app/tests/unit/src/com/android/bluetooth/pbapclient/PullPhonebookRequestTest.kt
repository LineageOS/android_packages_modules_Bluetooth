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
import com.android.obex.ClientSession
import com.android.obex.HeaderSet
import com.android.obex.Operation
import com.android.obex.ResponseCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for {@link PullPhonebookRequestTest}. */
@RunWith(AndroidJUnit4::class)
class PullPhonebookRequestTest {

    private lateinit var server: FakePbapObexServer
    private lateinit var session: ClientSession
    private lateinit var request: PullPhonebookRequest

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
        request = PullPhonebookRequest(PHONEBOOK_NAME, params)
    }

    @Test
    fun getType_returnsTypeMetadataRequest() {
        assertThat(request.type).isEqualTo(PbapClientRequest.TYPE_PULL_PHONEBOOK)
    }

    @Test
    fun getResponseCode_beforeExecutingRequest_returnsNegativeOne() {
        assertThat(request.responseCode).isEqualTo(-1)
    }

    @Test
    fun executeRequest_sessionConnectedWithContacts_returnsContacts() {
        session.connect(null)

        val vcard =
            Utils.createVcard(
                Utils.VERSION_30,
                "Foo",
                "Bar",
                "+1-234-567-8901",
                "111 Test Street;Test Town;CA;90210;USA",
                "Foo@email.com",
            )
        server.addContact(vcard)

        request.execute(session)

        assertThat(request.responseCode).isEqualTo(ResponseCodes.OBEX_HTTP_OK)
        assertThat(request.phonebook).isEqualTo(PHONEBOOK_NAME)

        val phonebook = request.contacts
        assertThat(phonebook).isNotNull()
        assertThat(phonebook.phonebook).isEqualTo(PHONEBOOK_NAME)
        assertThat(phonebook.offset).isEqualTo(0)
        assertThat(phonebook.count).isEqualTo(1)
        assertThat(phonebook.list).isNotEmpty()
    }

    @Test
    fun execute_sessionConnectedAndResponseBad_returnsEmptyPhonebook() {
        session.connect(null)
        server.setResponseCode(ResponseCodes.OBEX_HTTP_BAD_REQUEST)

        request.execute(session)

        assertThat(request.responseCode).isEqualTo(ResponseCodes.OBEX_HTTP_BAD_REQUEST)
        assertThat(request.phonebook).isEqualTo(PHONEBOOK_NAME)

        val phonebook = request.contacts
        assertThat(phonebook).isNotNull()
        assertThat(phonebook.phonebook).isEqualTo(PHONEBOOK_NAME)
        assertThat(phonebook.offset).isEqualTo(0)
        assertThat(phonebook.count).isEqualTo(0)
        assertThat(phonebook.list).isEmpty()
    }

    // *********************************************************************************************
    // * Fake PBAP Server
    // *********************************************************************************************

    private class FakePbapObexServer : FakeObexServer() {
        private var responseCode = ResponseCodes.OBEX_HTTP_OK
        private val phonebook = mutableListOf<String>()

        fun setResponseCode(responseCode: Int) {
            this.responseCode = responseCode
        }

        fun addContact(vcard: String) {
            phonebook.add(vcard)
        }

        override fun onGet(op: Operation): Int {
            if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                return responseCode
            }

            var contacts: ByteArray? = null
            if (phonebook.isNotEmpty()) {
                val phonebookString = Utils.createPhonebook(phonebook)
                contacts = phonebookString.toByteArray()
            }

            val replyHeaders = HeaderSet()
            return sendResponse(op, replyHeaders, contacts)
        }
    }

    companion object {
        private const val PHONEBOOK_NAME = "phonebook"
    }
}
