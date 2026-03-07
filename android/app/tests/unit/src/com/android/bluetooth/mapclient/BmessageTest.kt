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

package com.android.bluetooth.mapclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [Bmessage]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class BmessageTest {

    @Test
    fun testNormalMessages() {
        assertThat(BmessageParser.createBmessage(SIMPLE_MMS_MESSAGE)).isNotNull()
    }

    @Test
    fun testParseWrongLengthMessage() {
        assertThat(BmessageParser.createBmessage(WRONG_LENGTH_MESSAGE)).isNull()
    }

    @Test
    fun testParseNoEndMessage() {
        assertThat(BmessageParser.createBmessage(NO_END_MESSAGE)).isNull()
    }

    @Test
    fun testParseReallyLongMessage() {
        val testMessage = "A".repeat(68048)
        assertThat(BmessageParser.createBmessage(testMessage)).isNull()
    }

    @Test
    fun testNoBodyMessage() {
        assertThat(BmessageParser.createBmessage(NO_BODY_MESSAGE)).isNull()
    }

    @Test
    fun testNegativeLengthMessage() {
        assertThat(BmessageParser.createBmessage(NEGATIVE_LENGTH_MESSAGE)).isNull()
    }

    @Test
    fun setCharset() {
        val message = Bmessage().apply { charset = "UTF-8" }
        assertThat(message.charset).isEqualTo("UTF-8")
    }

    @Test
    fun setEncoding() {
        val message = Bmessage().apply { encoding = "test_encoding" }
        assertThat(message.encoding).isEqualTo("test_encoding")
    }

    @Test
    fun setStatus() {
        val message = Bmessage().apply { status = Bmessage.Status.READ }
        assertThat(message.status).isEqualTo(Bmessage.Status.READ)
    }

    companion object {
        // `trimIndent` removes the last `\r\n` whereas [BMessage] expects it
        private fun String.toBmessage() = trimIndent().replace(Regex("\n *"), "\r\n").plus("\r\n")

        private val SIMPLE_MMS_MESSAGE =
            """
            BEGIN:BMSG
                VERSION:1.0
                STATUS:READ
                TYPE:MMS
                FOLDER:null
                BEGIN:BENV
                    BEGIN:VCARD
                        VERSION:2.1
                        N:null;;;;
                        TEL:555-5555
                    END:VCARD
                    BEGIN:BBODY
                        LENGTH:39
                        BEGIN:MSG
                            This is a new msg
                        END:MSG
                    END:BBODY
                END:BENV
            END:BMSG
            """
                .toBmessage()

        private val NO_END_MESSAGE =
            """
            BEGIN:BMSG
                VERSION:1.0
                STATUS:READ
                TYPE:MMS
                FOLDER:null
                BEGIN:BENV
                    BEGIN:VCARD
                        VERSION:2.1
                        N:null;;;;
                        TEL:555-5555
                    END:VCARD
                    BEGIN:BBODY
                        LENGTH:200
                        BEGIN:MSG
                            This is a new msg
            """
                .toBmessage()

        private val WRONG_LENGTH_MESSAGE =
            """
            BEGIN:BMSG
                VERSION:1.0
                STATUS:READ
                TYPE:MMS
                FOLDER:null
                BEGIN:BENV
                    BEGIN:VCARD
                        VERSION:2.1
                        N:null;;;;
                        TEL:555-5555
                    END:VCARD
                    BEGIN:BBODY
                        LENGTH:200
                        BEGIN:MSG
                            This is a new msg
                        END:MSG
                    END:BBODY
                END:BENV
            END:BMSG
            """
                .toBmessage()

        private val NO_BODY_MESSAGE =
            """
            BEGIN:BMSG
                VERSION:1.0
                STATUS:READ
                TYPE:MMS
                FOLDER:null
                BEGIN:BENV
                    BEGIN:VCARD
                        VERSION:2.1
                        N:null;;;;
                        TEL:555-5555
                    END:VCARD
                    BEGIN:BBODY
                        LENGTH:
            """
                .toBmessage()

        private val NEGATIVE_LENGTH_MESSAGE =
            """
            BEGIN:BMSG
                VERSION:1.0
                STATUS:READ
                TYPE:MMS
                FOLDER:null
                BEGIN:BENV
                    BEGIN:VCARD
                        VERSION:2.1
                        N:null;;;;
                        TEL:555-5555
                    END:VCARD
                    BEGIN:BBODY
                        LENGTH:-1
                        BEGIN:MSG
                            This is a new msg
                        END:MSG
                    END:BBODY
                END:BENV
            END:BMSG
            """
                .toBmessage()
    }
}
