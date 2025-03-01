/*
 * Copyright 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class PbapPhonebookTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    // *********************************************************************************************
    // * Create Phonebook
    // *********************************************************************************************

    @Test
    public void testCreatePhonebook_forFavorites_emptyFavoritesPhonebookeCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.FAVORITES_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.FAVORITES_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forLocalPhonebook_emptyLocalPhonebookeCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forIncomingCallHistory_emptyIncomingCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.ICH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.ICH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forOutgoingCallHistory_emptyOutgoingCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.OCH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.OCH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forMissedCallHistory_emptyMissedCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.MCH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.MCH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forSimIncomingCallHistory_emptySimIncomingCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.SIM_ICH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_ICH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forSimOutgoingCallHistory_emptySimOutgoingCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.SIM_OCH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_OCH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    @Test
    public void testCreatePhonebook_forSimMissedCallHistory_emptyMiSimssedCallHistoryCreated()
            throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.SIM_MCH_PATH);
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_MCH_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isEmpty();
    }

    // *********************************************************************************************
    // * Parse Phonebook
    // *********************************************************************************************

    @Test
    public void testParsePhonebook_forFavorites_favoritesParsed() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Baz@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.FAVORITES_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.FAVORITES_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forLocalPhonebook_localPhonebookParsed() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Baz@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.LOCAL_PHONEBOOK_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forSimPhonebook_simPhonebookParsed() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Baz@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_PHONEBOOK_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_PHONEBOOK_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    // *********************************************************************************************
    // * Parse Call History
    // *********************************************************************************************

    @Test
    public void testParsePhonebook_forIncomingCallHistory_incomingCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.INCOMING_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.INCOMING_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.ICH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.ICH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forOutgoingCallHistory_outgoingCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.OUTGOING_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.OUTGOING_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.OCH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.OCH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forMissedCallHistory_missedCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.MISSED_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.MISSED_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.MCH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.MCH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forSimIncomingCallHistory_simIncomingCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.INCOMING_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.INCOMING_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_ICH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_ICH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forSimOutgoingCallHistory_simOutgoingCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.OUTGOING_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.OUTGOING_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_OCH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_OCH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParsePhonebook_forSimMissedCallHistory_simMissedCallHistoryParsed()
            throws IOException {
        String call1 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.MISSED_CALL,
                        "20240101T100000",
                        "Foo",
                        "Bar",
                        "+12345678901");
        String call2 =
                Utils.createCallHistory(
                        Utils.VERSION_21,
                        Utils.MISSED_CALL,
                        "20240101T110000",
                        "Baz",
                        "Bar",
                        "+12345678902");
        String historyString = Utils.createPhonebook(Arrays.asList(new String[] {call1, call2}));

        InputStream stream = toUtf8Stream(historyString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_MCH_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_MCH_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    // *********************************************************************************************
    // * Parsing Edge Cases
    // *********************************************************************************************

    @Test
    public void testParse21Phonebook_reportedAs30_parsedCorrectly() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_21,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Baz@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_PHONEBOOK_PATH,
                        PbapPhonebook.FORMAT_VCARD_30,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_PHONEBOOK_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParse30Phonebook_reportedAs21_parsedCorrectly() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Baz@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_PHONEBOOK_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_PHONEBOOK_PATH);
        assertThat(phonebook.getCount()).isEqualTo(2);
    }

    @Test
    public void testParseUnsupportedPhonebook_reportedAs21_parsingFails() throws IOException {
        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_UNSUPPORTED,
                        "Foo",
                        "Bar",
                        "+12345678901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "PORTED@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_UNSUPPORTED,
                        "Baz",
                        "Bar",
                        "+12345678902",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "PORTED@email.com");
        String phonebookString =
                Utils.createPhonebook(Arrays.asList(new String[] {vcard1, vcard2}));

        InputStream stream = toUtf8Stream(phonebookString);
        PbapPhonebook phonebook =
                new PbapPhonebook(
                        PbapPhonebook.SIM_PHONEBOOK_PATH,
                        PbapPhonebook.FORMAT_VCARD_21,
                        0,
                        stream);

        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.SIM_PHONEBOOK_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(0);
        assertThat(phonebook.getList()).isNotNull();
        assertThat(phonebook.getList()).isEmpty();
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testPhonebookToString() throws IOException {
        PbapPhonebook phonebook = new PbapPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        String str = phonebook.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    // *********************************************************************************************
    // * Utilities
    // *********************************************************************************************

    private static InputStream toUtf8Stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
