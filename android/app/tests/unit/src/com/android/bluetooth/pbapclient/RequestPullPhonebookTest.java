/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.FakeObexServer;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.Operation;
import com.android.obex.ResponseCodes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link RequestPullPhonebook}. */
@RunWith(AndroidJUnit4.class)
public class RequestPullPhonebookTest {

    private static final String PHONEBOOK_NAME = "phonebook";

    private FakePbapObexServer mServer;
    private ClientSession mSession;

    private RequestPullPhonebook mRequest;

    @Before
    public void setUp() throws IOException {
        mServer = new FakePbapObexServer();
        mSession = mServer.getClientSession();

        PbapApplicationParameters params =
                new PbapApplicationParameters(
                        PbapApplicationParameters.PROPERTIES_ALL,
                        PbapPhonebook.FORMAT_VCARD_30,
                        PbapApplicationParameters.MAX_PHONEBOOK_SIZE,
                        /* startOffset= */ 0);
        mRequest = new RequestPullPhonebook(PHONEBOOK_NAME, params);
    }

    @Test
    public void getType_returnsTypeMetadataRequest() {
        assertThat(mRequest.getType()).isEqualTo(PbapClientRequest.TYPE_PULL_PHONEBOOK);
    }

    @Test
    public void getResponseCode_beforeExecutingRequest_returnsNegativeOne() {
        assertThat(mRequest.getResponseCode()).isEqualTo(-1);
    }

    @Test
    public void executeRequest_sessionConnectedWithContacts_returnsContacts() throws IOException {
        mSession.connect(null);

        String vcard =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+1-234-567-8901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        mServer.addContact(vcard);

        mRequest.execute(mSession);

        assertThat(mRequest.getResponseCode()).isEqualTo(ResponseCodes.OBEX_HTTP_OK);
        assertThat(mRequest.getPhonebook()).isEqualTo(PHONEBOOK_NAME);

        PbapPhonebook phonebook = mRequest.getContacts();
        assertThat(phonebook).isNotNull();
        assertThat(phonebook.getPhonebook()).isEqualTo(PHONEBOOK_NAME);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(1);
        assertThat(phonebook.getList()).isNotEmpty();
    }

    @Test
    public void execute_sessionConnectedAndResponseBad_returnsEmptyPhonebook() throws IOException {
        mSession.connect(null);
        mServer.setResponseCode(ResponseCodes.OBEX_HTTP_BAD_REQUEST);

        mRequest.execute(mSession);

        assertThat(mRequest.getResponseCode()).isEqualTo(ResponseCodes.OBEX_HTTP_BAD_REQUEST);
        assertThat(mRequest.getPhonebook()).isEqualTo(PHONEBOOK_NAME);

        PbapPhonebook phonebook = mRequest.getContacts();
        assertThat(phonebook).isNotNull();
        assertThat(phonebook.getPhonebook()).isEqualTo(PHONEBOOK_NAME);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    // *********************************************************************************************
    // * Fake PBAP Server
    // *********************************************************************************************

    private static class FakePbapObexServer extends FakeObexServer {
        private static final String TAG = FakePbapObexServer.class.getSimpleName();

        private int mResponseCode = ResponseCodes.OBEX_HTTP_OK;
        private final List<String> mPhonebook = new ArrayList<>();

        FakePbapObexServer() throws IOException {
            super();
        }

        public void setResponseCode(int responseCode) {
            mResponseCode = responseCode;
        }

        public void addContact(String vcard) {
            mPhonebook.add(vcard);
        }

        @Override
        public int onGet(final Operation op) {
            if (mResponseCode != ResponseCodes.OBEX_HTTP_OK) {
                return mResponseCode;
            }

            byte[] contacts = null;
            if (mPhonebook.size() > 0) {
                String phonebook = Utils.createPhonebook(mPhonebook);
                contacts = phonebook.getBytes();
            }

            HeaderSet replyHeaders = new HeaderSet();
            return sendResponse(op, replyHeaders, contacts);
        }
    }
}
