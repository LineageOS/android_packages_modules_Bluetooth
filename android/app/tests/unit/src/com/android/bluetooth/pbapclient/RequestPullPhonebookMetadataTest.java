/*
 * Copyright 2022 The Android Open Source Project
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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.FakeObexServer;
import com.android.obex.ApplicationParameter;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.Operation;
import com.android.obex.ResponseCodes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.ByteBuffer;

@RunWith(AndroidJUnit4.class)
public class RequestPullPhonebookMetadataTest {
    private static final String PHONEBOOK_NAME = "phonebook";
    private static final short PHONEBOOK_SIZE = 200;

    private FakePbapObexServer mServer;
    private ClientSession mSession;
    private RequestPullPhonebookMetadata mRequest;

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
        mRequest = new RequestPullPhonebookMetadata(PHONEBOOK_NAME, params);
    }

    @Test
    public void getType_returnsTypeMetadataRequest() {
        assertThat(mRequest.getType()).isEqualTo(PbapClientRequest.TYPE_PULL_PHONEBOOK_METADATA);
    }

    @Test
    public void getResponseCode_beforeExecutingRequest_returnsNegativeOne() {
        assertThat(mRequest.getResponseCode()).isEqualTo(-1);
    }

    @Test
    public void execute_sessionConnectedAndResponseOk_returnsMetadata() throws IOException {
        mSession.connect(null);
        mServer.setSize(PHONEBOOK_SIZE);

        mRequest.execute(mSession);

        assertThat(mRequest.getResponseCode()).isEqualTo(ResponseCodes.OBEX_HTTP_OK);
        assertThat(mRequest.getPhonebook()).isEqualTo(PHONEBOOK_NAME);

        PbapPhonebookMetadata metadata = mRequest.getMetadata();
        assertThat(metadata.getPhonebook()).isEqualTo(PHONEBOOK_NAME);
        assertThat(metadata.getSize()).isEqualTo(200);
        assertThat(metadata.getDatabaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.getPrimaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.getSecondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
    }

    @Test
    public void execute_sessionConnectedAndResponseBad_returnsEmptyMetadata() throws IOException {
        mSession.connect(null);
        mServer.setResponseCode(ResponseCodes.OBEX_HTTP_BAD_REQUEST);
        mRequest.execute(mSession);

        assertThat(mRequest.getResponseCode()).isEqualTo(ResponseCodes.OBEX_HTTP_BAD_REQUEST);
        assertThat(mRequest.getPhonebook()).isEqualTo(PHONEBOOK_NAME);

        PbapPhonebookMetadata metadata = mRequest.getMetadata();
        assertThat(metadata.getPhonebook()).isEqualTo(PHONEBOOK_NAME);
        assertThat(metadata.getSize()).isEqualTo(PbapPhonebookMetadata.INVALID_SIZE);
        assertThat(metadata.getDatabaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.getPrimaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.getSecondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
    }

    @Test
    public void execute_sessionNotConnected_throwsIOException() throws IOException {
        assertThrows(IOException.class, () -> mRequest.execute(mSession));
        assertThat(mRequest.getResponseCode()).isEqualTo(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR);
    }

    @Test
    public void readResponseHeaders() {
        try {
            HeaderSet headerSet = new HeaderSet();
            mRequest.readResponseHeaders(headerSet);
            assertThat(mRequest.getMetadata().getSize())
                    .isEqualTo(PbapPhonebookMetadata.INVALID_SIZE);
        } catch (Exception e) {
            assertWithMessage("Exception should not happen.").fail();
        }
    }

    // *********************************************************************************************
    // * Fake PBAP Server
    // *********************************************************************************************

    private static class FakePbapObexServer extends FakeObexServer {
        private static final byte SIZE_BYTES = 2;

        private int mResponseCode = ResponseCodes.OBEX_HTTP_OK;
        private short mSize = 0;

        FakePbapObexServer() throws IOException {
            super();
        }

        public void setResponseCode(int responseCode) {
            mResponseCode = responseCode;
        }

        public void setSize(short size) {
            mSize = size;
        }

        @Override
        public int onGet(final Operation op) {
            if (mResponseCode != ResponseCodes.OBEX_HTTP_OK) {
                return mResponseCode;
            }

            ApplicationParameter params = new ApplicationParameter();
            params.addTriplet(
                    PbapApplicationParameters.OAP_PHONEBOOK_SIZE,
                    SIZE_BYTES,
                    shortToByteArray(mSize));

            HeaderSet replyHeaders = new HeaderSet();
            replyHeaders.setHeader(HeaderSet.APPLICATION_PARAMETER, params.getHeader());
            return sendResponse(op, replyHeaders, null);
        }

        public static byte[] shortToByteArray(short s) {
            ByteBuffer ret = ByteBuffer.allocate(2);
            ret.putShort(s);
            return ret.array();
        }
    }
}
