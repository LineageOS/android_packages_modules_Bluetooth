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

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.exception.VCardException;
import com.android.vcard.exception.VCardVersionException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class PbapPhonebook {
    private static final String TAG = PbapPhonebook.class.getSimpleName();

    // {@link BufferedInputStream#DEFAULT_BUFFER_SIZE} is not public
    private static final int BIS_DEFAULT_BUFFER_SIZE = 8192;

    // Phonebooks, including call history. See PBAP 1.2.3, Section 3.1.2
    public static final String LOCAL_PHONEBOOK_PATH = "telecom/pb.vcf"; // Device phonebook
    public static final String FAVORITES_PATH = "telecom/fav.vcf"; // Contacts marked as favorite
    public static final String MCH_PATH = "telecom/mch.vcf"; // Missed Calls
    public static final String ICH_PATH = "telecom/ich.vcf"; // Incoming Calls
    public static final String OCH_PATH = "telecom/och.vcf"; // Outgoing Calls
    public static final String SIM_PHONEBOOK_PATH = "SIM1/telecom/pb.vcf"; // SIM stored phonebook
    public static final String SIM_MCH_PATH = "SIM1/telecom/mch.vcf"; // SIM stored Missed Calls
    public static final String SIM_ICH_PATH = "SIM1/telecom/ich.vcf"; // SIM stored Incoming Calls
    public static final String SIM_OCH_PATH = "SIM1/telecom/och.vcf"; // SIM stored Outgoing Calls

    // VCard Formats, both are required to be supported by the Server, PBAP 1.2.3, Section 5.1.4.2
    public static byte FORMAT_VCARD_21 = 0;
    public static byte FORMAT_VCARD_30 = 1;

    private final String mPhonebook;
    private final int mListStartOffset;
    private final List<VCardEntry> mCards = new ArrayList<VCardEntry>();

    class CardEntryHandler implements VCardEntryHandler {
        @Override
        public void onStart() {}

        @Override
        public void onEntryCreated(VCardEntry entry) {
            mCards.add(entry);
        }

        @Override
        public void onEnd() {}
    }

    PbapPhonebook(String phonebook) {
        mPhonebook = phonebook;
        mListStartOffset = 0;
    }

    PbapPhonebook(
            String phonebook,
            byte format,
            int listStartOffset,
            InputStream inputStream)
            throws IOException {
        if (format != FORMAT_VCARD_21 && format != FORMAT_VCARD_30) {
            throw new IllegalArgumentException("Unsupported vCard version.");
        }
        mPhonebook = phonebook;
        mListStartOffset = listStartOffset;
        parse(inputStream, format);
    }

    private void parse(InputStream in, byte format) throws IOException {
        VCardParser parser;

        if (format == FORMAT_VCARD_30) {
            parser = new VCardParser_V30();
        } else {
            parser = new VCardParser_V21();
        }

        VCardEntryConstructor constructor =
                new VCardEntryConstructor(VCardConfig.VCARD_TYPE_V21_GENERIC);

        CardEntryHandler handler = new CardEntryHandler();
        constructor.addEntryHandler(handler);

        parser.addInterpreter(constructor);

        // {@link BufferedInputStream} supports the {@link InputStream#mark} and
        // {@link InputStream#reset} methods.
        BufferedInputStream bufferedInput = new BufferedInputStream(in);
        bufferedInput.mark(BIS_DEFAULT_BUFFER_SIZE /* readlimit */);

        // If there is a {@link VCardVersionException}, try parsing again with a different
        // version. Otherwise, parsing either succeeds (i.e., no {@link VCardException}) or it
        // fails with a different {@link VCardException}.
        if (parsedWithVcardVersionException(parser, bufferedInput)) {
            // PBAP v1.2.3 only supports vCard versions 2.1 and 3.0; it's one or the other
            if (format == FORMAT_VCARD_21) {
                parser = new VCardParser_V30();
                Log.w(TAG, "vCard version and Parser mismatch; expected v2.1, switching to v3.0");
            } else {
                parser = new VCardParser_V21();
                Log.w(TAG, "vCard version and Parser mismatch; expected v3.0, switching to v2.1");
            }
            // reset and try again
            bufferedInput.reset();
            mCards.clear();
            constructor.clear();
            parser.addInterpreter(constructor);
            if (parsedWithVcardVersionException(parser, bufferedInput)) {
                Log.e(TAG, "unsupported vCard version, neither v2.1 nor v3.0");
            }
        }
    }

    /**
     * Attempts to parse, with an eye on whether the correct version of Parser is used.
     *
     * @param parser -- the {@link VCardParser} to use.
     * @param in -- the {@link InputStream} to parse.
     * @return {@code true} if there was a {@link VCardVersionException}; {@code false} if there is
     *     any other {@link VCardException} or succeeds (i.e., no {@link VCardException}).
     * @throws IOException if there's an issue reading the {@link InputStream}.
     */
    private static boolean parsedWithVcardVersionException(VCardParser parser, InputStream in)
            throws IOException {
        try {
            parser.parse(in);
        } catch (VCardVersionException e1) {
            Log.w(TAG, "vCard version and Parser mismatch", e1);
            return true;
        } catch (VCardException e2) {
            Log.e(TAG, "vCard exception", e2);
        }
        return false;
    }

    /**
     * Get the phonebook path associated with this PbapPhonebook object
     *
     * @return a string representing the path these VCard objects were requested from
     */
    public String getPhonebook() {
        return mPhonebook;
    }

    /**
     * Get the offset associated with this PbapPhonebook object
     *
     * <p>The offset represents the start index of the remote contacts pull
     *
     * @return an int representing the offset index where this pull started from
     */
    public int getOffset() {
        return mListStartOffset;
    }

    /**
     * Get the total number of contacts contained in this phonebook
     *
     * @return an int containing the total number of contacts contained in this phonebook
     */
    public int getCount() {
        return mCards.size();
    }

    /**
     * Get the list of VCard objects contained in this phonebook
     *
     * @return a list of VCard objects in this phonebook
     */
    public List<VCardEntry> getList() {
        return mCards;
    }

    @Override
    public String toString() {
        return "<" + TAG + "phonebook=" + mPhonebook + " entries=" + getCount() + ">";
    }
}
