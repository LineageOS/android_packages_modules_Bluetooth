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

package com.android.bluetooth.obex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.obex.HeaderSet
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [ObexAppParameters]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ObexAppParametersTest {
    @Test
    fun constructorWithByteArrays_withOneInvalidElement() {
        val length = 4

        val byteArray =
            byteArrayOf(
                KEY,
                length.toByte(),
                0x12,
                0x34,
                0x56,
                0x78,
                0x66,
            ) // Last one is invalid. It will be filtered out.

        val params = ObexAppParameters(byteArray)
        assertThat(params.exists(KEY)).isTrue()

        val expected = byteArray.copyOfRange(2, 6)
        assertThat(params.getByteArray(KEY)).isEqualTo(expected)
    }

    @Test
    fun constructorWithByteArrays_withTwoInvalidElements() {
        val length = 4
        val byteArray =
            byteArrayOf(
                KEY,
                length.toByte(),
                0x12,
                0x34,
                0x56,
                0x78,
                0x66,
                0x77,
            ) // Last two are invalid. It will be filtered out.

        val params = ObexAppParameters(byteArray)
        assertThat(params.exists(KEY)).isTrue()

        val expected = byteArray.copyOfRange(2, 6)
        assertThat(params.getByteArray(KEY)).isEqualTo(expected)
    }

    @Test
    fun fromHeaderSet() {
        val length = 4
        val byteArray = byteArrayOf(KEY, length.toByte(), 0x12, 0x34, 0x56, 0x78)

        val headerSet = HeaderSet()
        headerSet.setHeader(HeaderSet.APPLICATION_PARAMETER, byteArray)

        val params = ObexAppParameters.fromHeaderSet(headerSet)
        assertThat(params).isNotNull()

        val expected = byteArray.copyOfRange(2, 6)
        assertThat(params.getByteArray(KEY)).isEqualTo(expected)
    }

    @Test
    fun addToHeaderSet() {
        val length = 4
        val byteArray = byteArrayOf(KEY, length.toByte(), 0x12, 0x34, 0x56, 0x78)

        val headerSet = HeaderSet()
        val params = ObexAppParameters(byteArray)
        params.addToHeaderSet(headerSet)

        assertThat(byteArray).isEqualTo(headerSet.getHeader(HeaderSet.APPLICATION_PARAMETER))
    }

    @Test
    fun add_byte() {
        val params = ObexAppParameters()
        val value: Byte = 0x34
        params.add(KEY, value)

        assertThat(params.getByte(KEY)).isEqualTo(value)
    }

    @Test
    fun add_short() {
        val params = ObexAppParameters()
        val value: Short = 0x99 // More than max byte value
        params.add(KEY, value)

        assertThat(params.getShort(KEY)).isEqualTo(value)
    }

    @Test
    fun add_int() {
        val params = ObexAppParameters()
        val value = 12345678 // More than max short value
        params.add(KEY, value)

        assertThat(params.getInt(KEY)).isEqualTo(value)
    }

    @Test
    fun add_long() {
        val params = ObexAppParameters()
        val value = 1234567890123456L // More than max integer value
        params.add(KEY, value)

        // Note: getLong() does not exist
        val byteArray = params.getByteArray(KEY)
        assertThat(ByteBuffer.wrap(byteArray).getLong()).isEqualTo(value)
    }

    @Test
    fun add_string() {
        val params = ObexAppParameters()
        val value = "Some string value"
        params.add(KEY, value)

        assertThat(params.getString(KEY)).isEqualTo(value)
    }

    @Test
    fun add_byteArray() {
        val params = ObexAppParameters()
        val value = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        params.add(KEY, value)

        assertThat(params.getByteArray(KEY)).isEqualTo(value)
    }

    @Test
    fun get_errorCases() {
        val emptyParams = ObexAppParameters()

        assertThat(emptyParams.getByte(KEY)).isEqualTo(0)
        assertThat(emptyParams.getShort(KEY)).isEqualTo(0)
        assertThat(emptyParams.getInt(KEY)).isEqualTo(0)
        // Note: getLong() does not exist
        assertThat(emptyParams.getString(KEY)).isNull()
        assertThat(emptyParams.getByteArray(KEY)).isNull()
    }

    @Test
    fun toString_isNotNull() {
        val params = ObexAppParameters()
        assertThat(params.toString()).isNotNull()
    }

    @Test
    fun getHeader_withTwoEntries() {
        val params = ObexAppParameters()

        val key1: Byte = 0x01
        val value1 = 12345
        params.add(key1, value1)

        val key2: Byte = 0x02
        val value2 = 56789
        params.add(key2, value2)

        val result = ByteBuffer.wrap(params.getHeader())
        val firstKey = result.get()

        val sizeOfInt = 4
        when (firstKey) {
            key1 -> {
                assertThat(result.get()).isEqualTo(sizeOfInt)
                assertThat(result.getInt()).isEqualTo(value1)

                assertThat(result.get()).isEqualTo(key2)
                assertThat(result.get()).isEqualTo(sizeOfInt)
                assertThat(result.getInt()).isEqualTo(value2)
            }
            key2 -> {
                assertThat(result.get()).isEqualTo(sizeOfInt)
                assertThat(result.getInt()).isEqualTo(value2)

                assertThat(result.get()).isEqualTo(key1)
                assertThat(result.get()).isEqualTo(sizeOfInt)
                assertThat(result.getInt()).isEqualTo(value1)
            }
            else -> {
                assertWithMessage("Key should be one of two keys").fail()
            }
        }
    }

    companion object {
        private const val KEY: Byte = 0x12
    }
}
