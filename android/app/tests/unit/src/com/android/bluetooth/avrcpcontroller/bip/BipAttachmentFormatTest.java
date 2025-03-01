/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.SuppressLint;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/** A test suite for the BipAttachmentFormat class */
@RunWith(AndroidJUnit4.class)
public class BipAttachmentFormatTest {

    private static Date makeDate(
            int month, int day, int year, int hours, int min, int sec, TimeZone tz) {
        Calendar.Builder builder = new Calendar.Builder();

        /* Note that Calendar months are zero-based in Java framework */
        builder.setDate(year, month - 1, day);
        builder.setTimeOfDay(hours, min, sec, 0);
        if (tz != null) builder.setTimeZone(tz);
        return builder.build().getTime();
    }

    private static Date makeDate(int month, int day, int year, int hours, int min, int sec) {
        return makeDate(month, day, year, hours, min, sec, null);
    }

    @SuppressLint("UndefinedEquals")
    private static void testParse(
            String contentType,
            String charset,
            String name,
            String size,
            String created,
            String modified,
            Date expectedCreated,
            boolean isCreatedUtc,
            Date expectedModified,
            boolean isModifiedUtc) {
        int expectedSize = (size != null ? Integer.parseInt(size) : -1);
        BipAttachmentFormat attachment =
                new BipAttachmentFormat(contentType, charset, name, size, created, modified);
        assertThat(attachment.getContentType()).isEqualTo(contentType);
        assertThat(attachment.getCharset()).isEqualTo(charset);
        assertThat(attachment.getName()).isEqualTo(name);
        assertThat(attachment.getSize()).isEqualTo(expectedSize);

        if (expectedCreated != null) {
            assertThat(attachment.getCreatedDate().getTime()).isEqualTo(expectedCreated);
            assertThat(attachment.getCreatedDate().isUtc()).isEqualTo(isCreatedUtc);
        } else {
            assertThat(attachment.getCreatedDate()).isNull();
        }

        if (expectedModified != null) {
            assertThat(attachment.getModifiedDate().getTime()).isEqualTo(expectedModified);
            assertThat(attachment.getModifiedDate().isUtc()).isEqualTo(isModifiedUtc);
        } else {
            assertThat(attachment.getModifiedDate()).isNull();
        }
    }

    @SuppressLint("UndefinedEquals")
    private static void testCreate(
            String contentType,
            String charset,
            String name,
            int size,
            Date created,
            Date modified) {
        BipAttachmentFormat attachment =
                new BipAttachmentFormat(contentType, charset, name, size, created, modified);
        assertThat(attachment.getContentType()).isEqualTo(contentType);
        assertThat(attachment.getCharset()).isEqualTo(charset);
        assertThat(attachment.getName()).isEqualTo(name);
        assertThat(attachment.getSize()).isEqualTo(size);

        if (created != null) {
            assertThat(attachment.getCreatedDate().getTime()).isEqualTo(created);
            assertThat(attachment.getCreatedDate().isUtc()).isTrue();
        } else {
            assertThat(attachment.getCreatedDate()).isNull();
        }

        if (modified != null) {
            assertThat(attachment.getModifiedDate().getTime()).isEqualTo(modified);
            assertThat(attachment.getModifiedDate().isUtc()).isTrue();
        } else {
            assertThat(attachment.getModifiedDate()).isNull();
        }
    }

    @Test
    public void testParseAttachment() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        utc.setRawOffset(0);
        Date date = makeDate(1, 1, 1990, 12, 34, 56);
        Date dateUtc = makeDate(1, 1, 1990, 12, 34, 56, utc);

        // Well defined fields
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                "2048",
                "19900101T123456",
                "19900101T123456",
                date,
                false,
                date,
                false);

        // Well defined fields with UTC date
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                "2048",
                "19900101T123456Z",
                "19900101T123456Z",
                dateUtc,
                true,
                dateUtc,
                true);

        // Change up the content type and file name
        testParse(
                "audio/basic",
                "ISO-8859-1",
                "thisisawavfile.wav",
                "1024",
                "19900101T123456",
                "19900101T123456",
                date,
                false,
                date,
                false);

        // Use a null modified date
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                "2048",
                "19900101T123456",
                null,
                date,
                false,
                null,
                false);

        // Use a null created date
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                "2048",
                null,
                "19900101T123456",
                null,
                false,
                date,
                false);

        // Use all null dates
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                "123",
                null,
                null,
                null,
                false,
                null,
                false);

        // Use a null size
        testParse(
                "text/plain",
                "ISO-8859-1",
                "thisisatextfile.txt",
                null,
                null,
                null,
                null,
                false,
                null,
                false);

        // Use a null charset
        testParse(
                "text/plain",
                null,
                "thisisatextfile.txt",
                "2048",
                null,
                null,
                null,
                false,
                null,
                false);

        // Use only required fields
        testParse(
                "text/plain",
                null,
                "thisisatextfile.txt",
                null,
                null,
                null,
                null,
                false,
                null,
                false);
    }

    @Test(expected = ParseException.class)
    public void testParseNullContentType() {
        testParse(
                null,
                "ISO-8859-1",
                "thisisatextfile.txt",
                null,
                null,
                null,
                null,
                false,
                null,
                false);
    }

    @Test(expected = ParseException.class)
    public void testParseNullName() {
        testParse("text/plain", "ISO-8859-1", null, null, null, null, null, false, null, false);
    }

    @Test
    public void testCreateAttachment() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        utc.setRawOffset(0);
        Date date = makeDate(1, 1, 1990, 12, 34, 56);

        // Create with normal, well defined fields
        testCreate("text/plain", "ISO-8859-1", "thisisatextfile.txt", 2048, date, date);

        // Create with a null charset
        testCreate("text/plain", null, "thisisatextfile.txt", 2048, date, date);

        // Create with "no size"
        testCreate("text/plain", "ISO-8859-1", "thisisatextfile.txt", -1, date, date);

        // Use only required fields
        testCreate("text/plain", null, "thisisatextfile.txt", -1, null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testCreateNullContentType_throwsException() {
        testCreate(null, "ISO-8859-1", "thisisatextfile.txt", 2048, null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testCreateNullName_throwsException() {
        testCreate("text/plain", "ISO-8859-1", null, 2048, null, null);
    }

    @Test
    public void testParsedAttachmentToString() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        utc.setRawOffset(0);

        String expected =
                "<attachment content-type=\"text/plain\" charset=\"ISO-8859-1\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\""
                        + " created=\"19900101T123456\" modified=\"19900101T123456\" />";

        String expectedUtc =
                "<attachment content-type=\"text/plain\" charset=\"ISO-8859-1\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\""
                        + " created=\"19900101T123456Z\" modified=\"19900101T123456Z\" />";

        String expectedNoDates =
                "<attachment content-type=\"text/plain\" charset=\"ISO-8859-1\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\" />";

        String expectedNoSizeNoDates =
                "<attachment content-type=\"text/plain\""
                        + " charset=\"ISO-8859-1\" name=\"thisisatextfile.txt\" />";

        String expectedNoCharsetNoDates =
                "<attachment content-type=\"text/plain\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\" />";

        String expectedRequiredOnly =
                "<attachment content-type=\"text/plain\"" + " name=\"thisisatextfile.txt\" />";

        // Create by parsing, all fields
        BipAttachmentFormat attachment =
                new BipAttachmentFormat(
                        "text/plain",
                        "ISO-8859-1",
                        "thisisatextfile.txt",
                        "2048",
                        "19900101T123456",
                        "19900101T123456");
        assertThat(attachment.toString()).isEqualTo(expected);

        // Create by parsing, all fields with utc dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain",
                        "ISO-8859-1",
                        "thisisatextfile.txt",
                        "2048",
                        "19900101T123456Z",
                        "19900101T123456Z");
        assertThat(attachment.toString()).isEqualTo(expectedUtc);

        // Create by parsing, no timestamps
        attachment =
                new BipAttachmentFormat(
                        "text/plain", "ISO-8859-1", "thisisatextfile.txt", "2048", null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoDates);

        // Create by parsing, no size, no dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain", "ISO-8859-1", "thisisatextfile.txt", null, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoSizeNoDates);

        // Create by parsing, no charset, no dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain", null, "thisisatextfile.txt", "2048", null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoCharsetNoDates);

        // Create by parsing, content type only
        attachment =
                new BipAttachmentFormat(
                        "text/plain", null, "thisisatextfile.txt", null, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedRequiredOnly);
    }

    @Test
    public void testCreatedAttachmentToString() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        utc.setRawOffset(0);
        Date date = makeDate(1, 1, 1990, 12, 34, 56, utc);

        String expected =
                "<attachment content-type=\"text/plain\" charset=\"ISO-8859-1\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\""
                        + " created=\"19900101T123456Z\" modified=\"19900101T123456Z\" />";

        String expectedNoDates =
                "<attachment content-type=\"text/plain\" charset=\"ISO-8859-1\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\" />";

        String expectedNoSizeNoDates =
                "<attachment content-type=\"text/plain\""
                        + " charset=\"ISO-8859-1\" name=\"thisisatextfile.txt\" />";

        String expectedNoCharsetNoDates =
                "<attachment content-type=\"text/plain\""
                        + " name=\"thisisatextfile.txt\" size=\"2048\" />";

        String expectedRequiredOnly =
                "<attachment content-type=\"text/plain\"" + " name=\"thisisatextfile.txt\" />";

        // Create with objects, all fields. Now we Use UTC since all Date objects eventually become
        // UTC anyway and this will be timezone agnostic
        BipAttachmentFormat attachment =
                new BipAttachmentFormat(
                        "text/plain", "ISO-8859-1", "thisisatextfile.txt", 2048, date, date);
        assertThat(attachment.toString()).isEqualTo(expected);

        // Create with objects, no dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain", "ISO-8859-1", "thisisatextfile.txt", 2048, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoDates);

        // Create with objects, no size and no dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain", "ISO-8859-1", "thisisatextfile.txt", -1, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoSizeNoDates);

        // Create with objects, no charset, no dates
        attachment =
                new BipAttachmentFormat(
                        "text/plain", null, "thisisatextfile.txt", 2048, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedNoCharsetNoDates);

        // Create with objects, content type only
        attachment =
                new BipAttachmentFormat("text/plain", null, "thisisatextfile.txt", -1, null, null);
        assertThat(attachment.toString()).isEqualTo(expectedRequiredOnly);
    }

    @Test
    public void testEquals() {
        BipAttachmentFormat attachment =
                new BipAttachmentFormat("text/plain", null, "thisisatextfile.txt", -1, null, null);
        BipAttachmentFormat attachmentEqual =
                new BipAttachmentFormat("text/plain", null, "thisisatextfile.txt", -1, null, null);

        String notAttachment = "notAttachment";

        new EqualsTester()
                .addEqualityGroup(attachment, attachment, attachmentEqual)
                .addEqualityGroup(notAttachment)
                .testEquals();
    }
}
