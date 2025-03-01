/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.util.Log;

import com.android.obex.ClientOperation;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ResponseCodes;

import java.io.IOException;
import java.io.InputStream;

abstract class PbapClientRequest {
    private static final String TAG = PbapClientRequest.class.getSimpleName();

    // Request Types
    public static final int TYPE_PULL_PHONEBOOK_METADATA = 0;
    public static final int TYPE_PULL_PHONEBOOK = 1;

    protected HeaderSet mHeaderSet = new HeaderSet();
    private int mResponseCode = -1;

    PbapClientRequest() {
        mResponseCode = -1;
    }

    /**
     * A function that returns the type of the request.
     *
     * <p>Used to determine type instead of using 'instanceof'
     */
    public abstract int getType();

    /**
     * Get the actual response code associated with the request
     *
     * @return The response code as in integer
     */
    public final int getResponseCode() {
        return mResponseCode;
    }

    /**
     * A generica operation, providing overridable hooks to read response headers and content.
     *
     * <p>All PBAP Client operations are GET OBEX operations, so that is what this is.
     */
    public void execute(ClientSession session) throws IOException {
        Log.v(TAG, "execute");
        ClientOperation operation = null;
        try {
            operation = (ClientOperation) session.get(mHeaderSet);

            /* make sure final flag for GET is used (PBAP spec 6.2.2) */
            operation.setGetFinalFlag(true);

            /*
             * this will trigger ClientOperation to use non-buffered stream so
             * we can abort operation
             */
            operation.continueOperation(true, false);

            readResponseHeaders(operation.getReceivedHeader());
            InputStream inputStream = operation.openInputStream();
            readResponse(inputStream);
            inputStream.close();
            mResponseCode = operation.getResponseCode();
        } catch (IOException e) {
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            Log.e(TAG, "IOException occurred when processing request", e);
            throw e;
        } finally {
            // Always close the operation so the next operation can successfully complete
            if (operation != null) {
                operation.close();
            }
        }
    }

    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");
        /* nothing here by default */
    }

    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponseHeaders");
        /* nothing here by default */
    }

    public static String typeToString(int type) {
        switch (type) {
            case TYPE_PULL_PHONEBOOK_METADATA:
                return "TYPE_PULL_PHONEBOOK_METADATA";
            case TYPE_PULL_PHONEBOOK:
                return "TYPE_PULL_PHONEBOOK";
            default:
                return "TYPE_RESERVED (" + type + ")";
        }
    }

    @Override
    public String toString() {
        return "<"
                + TAG
                + (" type=" + typeToString(getType()))
                + (", responseCode=" + getResponseCode())
                + ">";
    }
}
