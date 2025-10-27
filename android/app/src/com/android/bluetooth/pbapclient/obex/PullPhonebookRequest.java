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
import com.android.vcard.VCardEntry;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

final class PullPhonebookRequest extends PbapClientRequest {
    private static final String TAG = PullPhonebookRequest.class.getSimpleName();

    private static final String TYPE = "x-bt/phonebook";

    private final String mPhonebook;
    private final byte mFormat;
    private final int mMaxListCount;
    private final int mListStartOffset;

    private PbapPhonebook mResponse;

    @Override
    public int getType() {
        return TYPE_PULL_PHONEBOOK;
    }

    PullPhonebookRequest(String phonebook, PbapApplicationParameters params) {
        mPhonebook = phonebook;
        mFormat = params.getVcardFormat();
        mMaxListCount = params.getMaxListCount();
        mListStartOffset = params.getListStartOffset();

        long properties = params.getPropertySelectorMask();

        mHeaderSet.setHeader(HeaderSet.NAME, phonebook);
        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        oap.add(PbapApplicationParameters.OAP_FORMAT, mFormat);

        if (properties != 0) {
            oap.add(PbapApplicationParameters.OAP_PROPERTY_SELECTOR, properties);
        }

        if (mListStartOffset > 0) {
            oap.add(PbapApplicationParameters.OAP_LIST_START_OFFSET, (short) mListStartOffset);
        }

        // maxListCount == 0 indicates to fetch all, in which case we set it to the upper bound
        // Note that Java has no unsigned types. To capture an unsigned value in the range [0, 2^16)
        // we need to use an int and cast to a short (2 bytes). This packs the bits we want.
        if (mMaxListCount > 0) {
            oap.add(PbapApplicationParameters.OAP_MAX_LIST_COUNT, (short) mMaxListCount);
        } else {
            oap.add(
                    PbapApplicationParameters.OAP_MAX_LIST_COUNT,
                    (short) PbapApplicationParameters.MAX_PHONEBOOK_SIZE);
        }

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        mResponse = new PbapPhonebook(mPhonebook, mFormat, mListStartOffset, stream);
    }

    public String getPhonebook() {
        return mPhonebook;
    }

    public List<VCardEntry> getList() {
        return mResponse.getList();
    }

    public PbapPhonebook getContacts() {
        return mResponse;
    }
}
