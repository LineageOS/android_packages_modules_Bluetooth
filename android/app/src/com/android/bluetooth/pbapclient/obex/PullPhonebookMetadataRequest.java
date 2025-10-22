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

import com.android.bluetooth.obex.ObexAppParameters;
import com.android.obex.HeaderSet;

/**
 * This implements a PullPhonebook request, with the goal of only fetching metadata for the given
 * phonebook, but not the actual phonebook contents itself.
 *
 * <p>This is done by requesting the phonebook, but omitting the MaxListCount parameter, signaling
 * that we're not interested in the contents (PBAP 1.2.3, Section 5.1, C7 of the Response Format
 * table)
 */
final class PullPhonebookMetadataRequest extends PbapClientRequest {
    private static final String TAG = PullPhonebookMetadataRequest.class.getSimpleName();

    private static final String TYPE = "x-bt/phonebook";

    private final String mPhonebook;
    private PbapPhonebookMetadata mResponse;

    @Override
    public int getType() {
        return TYPE_PULL_PHONEBOOK_METADATA;
    }

    PullPhonebookMetadataRequest(String phonebook, PbapApplicationParameters params) {
        mPhonebook = phonebook;
        mHeaderSet.setHeader(HeaderSet.NAME, phonebook);
        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        // Set MaxListCount in the request to 0 to get PhonebookSize in the response.
        // If a vCardSelector is present in the request, then the result shall
        // contain the number of items that satisfy the selector’s criteria.
        // See PBAP v1.2.3, Sec. 5.1.4.5.
        ObexAppParameters oap = new ObexAppParameters();
        oap.add(PbapApplicationParameters.OAP_MAX_LIST_COUNT, (short) 0);

        // Otherwise, listen to the property selector criteria passed in and ignore the rest
        long properties = params.getPropertySelectorMask();
        if (properties != PbapApplicationParameters.PROPERTIES_ALL) {
            oap.add(PbapApplicationParameters.OAP_PROPERTY_SELECTOR, properties);
        }
        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        int size = PbapPhonebookMetadata.INVALID_SIZE;
        String databaseIdentifier = PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER;
        String primaryVersionCounter = PbapPhonebookMetadata.INVALID_VERSION_COUNTER;
        String secondaryVersionCounter = PbapPhonebookMetadata.INVALID_VERSION_COUNTER;

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);
        if (oap.exists(PbapApplicationParameters.OAP_PHONEBOOK_SIZE)) {
            size = oap.getShort(PbapApplicationParameters.OAP_PHONEBOOK_SIZE);
        }

        mResponse =
                new PbapPhonebookMetadata(
                        mPhonebook,
                        size,
                        databaseIdentifier,
                        primaryVersionCounter,
                        secondaryVersionCounter);
    }

    public String getPhonebook() {
        return mPhonebook;
    }

    public PbapPhonebookMetadata getMetadata() {
        return mResponse;
    }
}
