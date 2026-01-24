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

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [BluetoothMapUtils]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class BluetoothMapUtilsTest {

    @Test
    fun encodeQuotedPrintable_withNullInput_returnsNull() {
        assertThat(BluetoothMapUtils.encodeQuotedPrintable(null)).isNull()
    }

    @Test
    fun encodeQuotedPrintable() {
        assertThat(
                BluetoothMapUtils.encodeQuotedPrintable(TEXT.toByteArray(StandardCharsets.UTF_8))
            )
            .isEqualTo(QUOTED_PRINTABLE_ENCODED_TEXT)
    }

    @Test
    fun quotedPrintableToUtf8() {
        assertThat(BluetoothMapUtils.quotedPrintableToUtf8(QUOTED_PRINTABLE_ENCODED_TEXT, null))
            .isEqualTo(TEXT.toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun printCursor_doesNotCrash() {
        val cursor = MatrixCursor(arrayOf(BluetoothMapContract.PresenceColumns.LAST_ONLINE, "Name"))
        cursor.addRow(arrayOf<Any>(345345226L, "test_name"))
        cursor.moveToFirst()

        BluetoothMapUtils.printCursor(cursor)
    }

    @Test
    fun stripEncoding_quotedPrintable() {
        assertThat(BluetoothMapUtils.stripEncoding("=?UTF-8?Q?$QUOTED_PRINTABLE_ENCODED_TEXT?="))
            .isEqualTo(TEXT)
    }

    @Test
    fun stripEncoding_base64() {
        assertThat(BluetoothMapUtils.stripEncoding("=?UTF-8?B?$BASE64_ENCODED_TEXT?="))
            .isEqualTo(TEXT)
    }

    companion object {
        private const val TEXT = "코드"
        private const val QUOTED_PRINTABLE_ENCODED_TEXT = "=EC=BD=94=EB=93=9C"
        private const val BASE64_ENCODED_TEXT = "7L2U65Oc"
    }
}
