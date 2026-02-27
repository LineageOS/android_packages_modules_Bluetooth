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

package com.android.bluetooth.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.map.BluetoothMapUtils.TYPE
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

/** Test cases for [BluetoothMapbMessageEmail]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothMapbMessageEmailTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var mapService: BluetoothMapService

    @Test
    fun setAndGetEmailBody() {
        val messageEmail = BluetoothMapbMessageEmail()
        messageEmail.emailBody = TEST_EMAIL_BODY
        assertThat(messageEmail.emailBody).isEqualTo(TEST_EMAIL_BODY)
    }

    @Test
    fun encodeToByteArray_thenCreateByParsing() {
        val messageEmailToEncode = BluetoothMapbMessageEmail()
        messageEmailToEncode.setType(TYPE.EMAIL)
        messageEmailToEncode.setFolder("placeholder")
        messageEmailToEncode.setStatus(true)
        messageEmailToEncode.setEmailBody(TEST_EMAIL_BODY)

        val encodedMessageEmail = messageEmailToEncode.encode()
        val inputStream = ByteArrayInputStream(encodedMessageEmail)

        val messageParsed =
            BluetoothMapbMessage.parse(mapService, inputStream, BluetoothMapAppParams.CHARSET_UTF8)
        assertThat(messageParsed).isInstanceOf(BluetoothMapbMessageEmail::class.java)
        val messageEmailParsed = messageParsed as BluetoothMapbMessageEmail
        assertThat(messageEmailParsed.emailBody).isEqualTo(TEST_EMAIL_BODY)
    }

    @Test
    fun encodeToByteArray_withEmptyBody_thenCreateByParsing() {
        val messageEmailToEncode = BluetoothMapbMessageEmail()
        messageEmailToEncode.setType(TYPE.EMAIL)
        messageEmailToEncode.setFolder("placeholder")
        messageEmailToEncode.setStatus(true)

        val encodedMessageEmail = messageEmailToEncode.encode()
        val inputStream = ByteArrayInputStream(encodedMessageEmail)

        val messageParsed =
            BluetoothMapbMessage.parse(mapService, inputStream, BluetoothMapAppParams.CHARSET_UTF8)
        assertThat(messageParsed).isInstanceOf(BluetoothMapbMessageEmail::class.java)
        val messageEmailParsed = messageParsed as BluetoothMapbMessageEmail
        assertThat(messageEmailParsed.emailBody).isEqualTo("")
    }

    companion object {
        const val TEST_EMAIL_BODY = "test_email_body"
    }
}
