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

package com.android.bluetooth.map;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.telephony.PhoneNumberUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessage.VCard;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test cases for {@link BluetoothMapbMessage}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothMapbMessageTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothMapService mMapService;

    private static final String TEST_VERSION_STRING = "1.0";
    private static final boolean TEST_STATUS = true;
    private static final TYPE TEST_TYPE = TYPE.IM;
    private static final String TEST_FOLDER = "placeholder";
    private static final String TEST_ENCODING = "test_encoding";

    private static final String TEST_NAME = "test_name";
    private static final String TEST_FORMATTED_NAME = "test_formatted_name";
    private static final String TEST_FIRST_PHONE_NUMBER = "111-1111-1111";
    private static final String[] TEST_PHONE_NUMBERS =
            new String[] {TEST_FIRST_PHONE_NUMBER, "222-2222-2222"};
    private static final String TEST_FIRST_EMAIL = "testFirst@email.com";
    private static final String[] TEST_EMAIL_ADDRESSES =
            new String[] {TEST_FIRST_EMAIL, "testSecond@email.com"};
    private static final String TEST_FIRST_BT_UCI = "test_first_bt_uci";
    private static final String[] TEST_BT_UCIS =
            new String[] {TEST_FIRST_BT_UCI, "test_second_bt_uci"};
    private static final String TEST_FIRST_BT_UID = "1111";
    private static final String[] TEST_BT_UIDS = new String[] {TEST_FIRST_BT_UID, "1112"};

    private static final VCard TEST_VCARD =
            new VCard(TEST_NAME, TEST_PHONE_NUMBERS, TEST_EMAIL_ADDRESSES);

    @Test
    public void settersAndGetters() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.setVersionString(TEST_VERSION_STRING);
        messageMime.setStatus(TEST_STATUS);
        messageMime.setType(TEST_TYPE);
        messageMime.setFolder(TEST_FOLDER);
        messageMime.setEncoding(TEST_ENCODING);
        messageMime.setRecipient(TEST_VCARD);

        assertThat(messageMime.getVersionString()).isEqualTo("VERSION:" + TEST_VERSION_STRING);
        assertThat(messageMime.getType()).isEqualTo(TEST_TYPE);
        assertThat(messageMime.getFolder()).isEqualTo("telecom/msg/" + TEST_FOLDER);
        assertThat(messageMime.getRecipients()).hasSize(1);
        assertThat(messageMime.getOriginators()).isNull();
    }

    @Test
    public void setCompleteFolder() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.setCompleteFolder(TEST_FOLDER);
        assertThat(messageMime.getFolder()).isEqualTo(TEST_FOLDER);
    }

    @Test
    public void addOriginator_forVCardVersionTwoPointOne() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addOriginator(TEST_NAME, TEST_PHONE_NUMBERS, TEST_EMAIL_ADDRESSES);
        assertThat(messageMime.getOriginators().get(0).getName()).isEqualTo(TEST_NAME);
        assertThat(messageMime.getOriginators().get(0).getFirstPhoneNumber())
                .isEqualTo(PhoneNumberUtils.stripSeparators(TEST_FIRST_PHONE_NUMBER));
        assertThat(messageMime.getOriginators().get(0).getFirstEmail()).isEqualTo(TEST_FIRST_EMAIL);
    }

    @Test
    public void addOriginator_withVCardObject() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addOriginator(TEST_VCARD);
        assertThat(messageMime.getOriginators().get(0)).isEqualTo(TEST_VCARD);
    }

    @Test
    public void addOriginator_forVCardVersionThree() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addOriginator(
                TEST_NAME,
                TEST_FORMATTED_NAME,
                TEST_PHONE_NUMBERS,
                TEST_EMAIL_ADDRESSES,
                TEST_BT_UIDS,
                TEST_BT_UCIS);
        assertThat(messageMime.getOriginators().get(0).getName()).isEqualTo(TEST_NAME);
        assertThat(messageMime.getOriginators().get(0).getFirstPhoneNumber())
                .isEqualTo(PhoneNumberUtils.stripSeparators(TEST_FIRST_PHONE_NUMBER));
        assertThat(messageMime.getOriginators().get(0).getFirstEmail()).isEqualTo(TEST_FIRST_EMAIL);
        assertThat(messageMime.getOriginators().get(0).getFirstBtUci())
                .isEqualTo(TEST_FIRST_BT_UCI);
    }

    @Test
    public void addOriginator_forVCardVersionThree_withOnlyBtUcisAndBtUids() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addOriginator(TEST_BT_UCIS, TEST_BT_UIDS);
        assertThat(messageMime.getOriginators().get(0).getFirstBtUci())
                .isEqualTo(TEST_FIRST_BT_UCI);
    }

    @Test
    public void addRecipient_forVCardVersionTwoPointOne() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addRecipient(TEST_NAME, TEST_PHONE_NUMBERS, TEST_EMAIL_ADDRESSES);
        assertThat(messageMime.getRecipients().get(0).getName()).isEqualTo(TEST_NAME);
        assertThat(messageMime.getRecipients().get(0).getFirstPhoneNumber())
                .isEqualTo(PhoneNumberUtils.stripSeparators(TEST_FIRST_PHONE_NUMBER));
        assertThat(messageMime.getRecipients().get(0).getFirstEmail()).isEqualTo(TEST_FIRST_EMAIL);
    }

    @Test
    public void addRecipient_forVCardVersionThree() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addRecipient(
                TEST_NAME,
                TEST_FORMATTED_NAME,
                TEST_PHONE_NUMBERS,
                TEST_EMAIL_ADDRESSES,
                TEST_BT_UIDS,
                TEST_BT_UCIS);
        assertThat(messageMime.getRecipients().get(0).getName()).isEqualTo(TEST_NAME);
        assertThat(messageMime.getRecipients().get(0).getFirstPhoneNumber())
                .isEqualTo(PhoneNumberUtils.stripSeparators(TEST_FIRST_PHONE_NUMBER));
        assertThat(messageMime.getRecipients().get(0).getFirstEmail()).isEqualTo(TEST_FIRST_EMAIL);
        assertThat(messageMime.getRecipients().get(0).getFirstBtUci()).isEqualTo(TEST_FIRST_BT_UCI);
    }

    @Test
    public void addRecipient_forVCardVersionThree_withOnlyBtUcisAndBtUids() {
        BluetoothMapbMessage messageMime = new BluetoothMapbMessageMime();
        messageMime.addRecipient(TEST_BT_UCIS, TEST_BT_UIDS);
        assertThat(messageMime.getRecipients().get(0).getFirstBtUci()).isEqualTo(TEST_FIRST_BT_UCI);
    }

    @Test
    public void encodeToByteArray_thenCreateByParsing_ReturnsCorrectly() throws Exception {
        BluetoothMapbMessage messageMimeToEncode = new BluetoothMapbMessageMime();
        messageMimeToEncode.setVersionString(TEST_VERSION_STRING);
        messageMimeToEncode.setStatus(TEST_STATUS);
        messageMimeToEncode.setType(TEST_TYPE);
        messageMimeToEncode.setCompleteFolder(TEST_FOLDER);
        messageMimeToEncode.setEncoding(TEST_ENCODING);
        messageMimeToEncode.addOriginator(
                TEST_NAME,
                TEST_FORMATTED_NAME,
                TEST_PHONE_NUMBERS,
                TEST_EMAIL_ADDRESSES,
                TEST_BT_UIDS,
                TEST_BT_UCIS);
        messageMimeToEncode.addRecipient(
                TEST_NAME,
                TEST_FORMATTED_NAME,
                TEST_PHONE_NUMBERS,
                TEST_EMAIL_ADDRESSES,
                TEST_BT_UIDS,
                TEST_BT_UCIS);

        byte[] encodedMessageMime = messageMimeToEncode.encode();
        InputStream inputStream = new ByteArrayInputStream(encodedMessageMime);

        BluetoothMapbMessage messageMimeParsed =
                BluetoothMapbMessage.parse(mMapService, inputStream, 1);
        assertThat(messageMimeParsed.mAppParamCharset).isEqualTo(1);
        assertThat(messageMimeParsed.getVersionString())
                .isEqualTo("VERSION:" + TEST_VERSION_STRING);
        assertThat(messageMimeParsed.getType()).isEqualTo(TEST_TYPE);
        assertThat(messageMimeParsed.getFolder()).isEqualTo(TEST_FOLDER);
        assertThat(messageMimeParsed.getRecipients()).hasSize(1);
        assertThat(messageMimeParsed.getOriginators()).hasSize(1);
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_singleLine() {
        String testString = "single line";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isEqualTo(testString);
        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_singleLineWithCRLF() {
        String testString = "single line";
        InputStream stream =
                new ByteArrayInputStream((testString + "\r\n").getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isEqualTo(testString);
        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_multipleLines() {
        String line1 = "Line 1";
        String line2 = "Line 2";
        String testString = line1 + "\r\n" + line2 + "\r\n";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isEqualTo(line1);
        assertThat(reader.getLine(false)).isEqualTo(line2);
        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_skipsLeadingEmptyLines() {
        String line1 = "Line 1";
        String testString = "\r\n\r\n" + line1 + "\r\n";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isEqualTo(line1);
        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_emptyStream_returnsNull() {
        InputStream stream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withoutNewline_onlyNewlines_returnsNull() {
        InputStream stream = new ByteArrayInputStream("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(false)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_singleLine() {
        String testString = "single line";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isEqualTo(testString);
        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_singleLineWithCRLF() {
        String testString = "single line\r\n";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isEqualTo(testString);
        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_multipleLines() {
        String line1 = "Line 1\r\n";
        String line2 = "Line 2\r\n";
        String testString = line1 + line2;
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isEqualTo(line1);
        assertThat(reader.getLine(true)).isEqualTo(line2);
        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_includesLeadingEmptyLines() {
        String line1 = "Line 1\r\n";
        String testString = "\r\n\r\n" + line1;
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isEqualTo(testString);
        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_emptyLineBetweenContent() {
        String line1 = "Line 1\r\n";
        String emptyLine = "\r\n";
        String line2 = "Line 2\r\n";
        String testString = line1 + emptyLine + line2;
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isEqualTo(line1);
        assertThat(reader.getLine(true)).isEqualTo(emptyLine + line2);
        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_emptyStream_returnsNull() {
        InputStream stream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLine_withNewline_onlyNewlines_returnsNull() {
        InputStream stream = new ByteArrayInputStream("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLine(true)).isNull();
    }

    @Test
    public void bMsgReader_getLineEnforce_withoutNewline_throwsExceptionOnEmptyStream() {
        InputStream stream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThrows(IllegalArgumentException.class, () -> reader.getLineEnforce());
    }

    @Test
    public void bMsgReader_getLineEnforce_withNewline_throwsExceptionOnEmptyStream() {
        InputStream stream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThrows(IllegalArgumentException.class, () -> reader.getLineEnforce(true));
    }

    @Test
    public void bMsgReader_getLineEnforce_withoutNewline_returnsLine() {
        String line1 = "Line 1";
        String testString = line1 + "\r\n";
        InputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLineEnforce()).isEqualTo(line1);
        assertThrows(IllegalArgumentException.class, () -> reader.getLineEnforce());
    }

    @Test
    public void bMsgReader_getLineEnforce_withNewline_returnsLine() {
        String line1 = "Line 1\r\n";
        InputStream stream = new ByteArrayInputStream(line1.getBytes(StandardCharsets.UTF_8));
        BluetoothMapbMessage.BMsgReader reader = new BluetoothMapbMessage.BMsgReader(stream);

        assertThat(reader.getLineEnforce(true)).isEqualTo(line1);
        assertThrows(IllegalArgumentException.class, () -> reader.getLineEnforce(true));
    }
}
