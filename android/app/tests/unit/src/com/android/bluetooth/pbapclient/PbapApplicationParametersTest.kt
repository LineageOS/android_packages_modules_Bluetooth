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

package com.android.bluetooth.pbapclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for {@link PbapApplicationParameters}. */
@RunWith(AndroidJUnit4::class)
class PbapApplicationParametersTest {

    @Test
    fun testCreateParams_paramsWellFormed() {
        val params =
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET,
            )

        assertThat(params.propertySelectorMask).isEqualTo(PbapApplicationParameters.PROPERTIES_ALL)
        assertThat(params.vcardFormat).isEqualTo(PbapPhonebook.FORMAT_VCARD_30)
        assertThat(params.maxListCount).isEqualTo(TEST_LIST_SIZE)
        assertThat(params.listStartOffset).isEqualTo(TEST_LIST_OFFSET)
        assertThat(params.toString()).isEqualTo(ALL_PROPERTIES_TO_STRING)
    }

    @Test
    fun testCreateParams_withPropertyFilter_paramsWellFormed() {
        val params =
            PbapApplicationParameters(
                (PbapApplicationParameters.PROPERTY_VERSION or
                    PbapApplicationParameters.PROPERTY_FN or
                    PbapApplicationParameters.PROPERTY_N),
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET,
            )

        assertThat(params.propertySelectorMask)
            .isEqualTo(
                (PbapApplicationParameters.PROPERTY_VERSION or
                    PbapApplicationParameters.PROPERTY_FN or
                    PbapApplicationParameters.PROPERTY_N)
            )
        assertThat(params.vcardFormat).isEqualTo(PbapPhonebook.FORMAT_VCARD_30)
        assertThat(params.maxListCount).isEqualTo(TEST_LIST_SIZE)
        assertThat(params.listStartOffset).isEqualTo(TEST_LIST_OFFSET)
        assertThat(params.toString()).isEqualTo(FILTER_PROPERTIES_TO_STRING)
    }

    @Test
    fun testCreateParams_withAllPropertiesIndividually_paramsWellFormed() {
        val params =
            PbapApplicationParameters(
                (PbapApplicationParameters.PROPERTY_VERSION or
                    PbapApplicationParameters.PROPERTY_FN or
                    PbapApplicationParameters.PROPERTY_N or
                    PbapApplicationParameters.PROPERTY_PHOTO or
                    PbapApplicationParameters.PROPERTY_ADR or
                    PbapApplicationParameters.PROPERTY_TEL or
                    PbapApplicationParameters.PROPERTY_EMAIL or
                    PbapApplicationParameters.PROPERTY_NICKNAME),
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET,
            )

        assertThat(params.propertySelectorMask)
            .isEqualTo(
                (PbapApplicationParameters.PROPERTY_VERSION or
                    PbapApplicationParameters.PROPERTY_FN or
                    PbapApplicationParameters.PROPERTY_N or
                    PbapApplicationParameters.PROPERTY_PHOTO or
                    PbapApplicationParameters.PROPERTY_ADR or
                    PbapApplicationParameters.PROPERTY_TEL or
                    PbapApplicationParameters.PROPERTY_EMAIL or
                    PbapApplicationParameters.PROPERTY_NICKNAME)
            )
        assertThat(params.vcardFormat).isEqualTo(PbapPhonebook.FORMAT_VCARD_30)
        assertThat(params.maxListCount).isEqualTo(TEST_LIST_SIZE)
        assertThat(params.listStartOffset).isEqualTo(TEST_LIST_OFFSET)
        assertThat(params.toString()).isEqualTo(FILTER_INDIVIDUAL_PROPERTIES_TO_STRING)
    }

    @Test
    fun testCreateParams_withFormat21_paramsWellFormed() {
        val params =
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_21,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET,
            )

        assertThat(params.propertySelectorMask).isEqualTo(PbapApplicationParameters.PROPERTIES_ALL)
        assertThat(params.vcardFormat).isEqualTo(PbapPhonebook.FORMAT_VCARD_21)
        assertThat(params.maxListCount).isEqualTo(TEST_LIST_SIZE)
        assertThat(params.listStartOffset).isEqualTo(TEST_LIST_OFFSET)
        assertThat(params.toString()).isEqualTo(FORMAT_21_TO_STRING)
    }

    @Test
    fun testCreateParams_maxListCountTooLarge_exceptionThrown() {
        assertThrows(IllegalArgumentException::class.java) {
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE_TOO_LARGE,
                TEST_LIST_OFFSET,
            )
        }
    }

    @Test
    fun testCreateParams_maxListCountTooSmall_exceptionThrown() {
        assertThrows(IllegalArgumentException::class.java) {
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE_TOO_SMALL,
                TEST_LIST_OFFSET,
            )
        }
    }

    @Test
    fun testCreateParams_OffsetTooLarge_exceptionThrown() {
        assertThrows(IllegalArgumentException::class.java) {
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET_TOO_LARGE,
            )
        }
    }

    @Test
    fun testCreateParams_OffsetTooSmall_exceptionThrown() {
        assertThrows(IllegalArgumentException::class.java) {
            PbapApplicationParameters(
                PbapApplicationParameters.PROPERTIES_ALL,
                PbapPhonebook.FORMAT_VCARD_30,
                TEST_LIST_SIZE,
                TEST_LIST_OFFSET_TOO_SMALL,
            )
        }
    }

    companion object {
        private const val TEST_LIST_SIZE = 250
        private const val TEST_LIST_SIZE_TOO_SMALL = -1
        private const val TEST_LIST_SIZE_TOO_LARGE = 65536

        private const val TEST_LIST_OFFSET = 0
        private const val TEST_LIST_OFFSET_TOO_SMALL = -1
        private const val TEST_LIST_OFFSET_TOO_LARGE = 65536

        private const val ALL_PROPERTIES_TO_STRING =
            "<PbapApplicationParameters properties=PROPERTIES_ALL format=1 maxListCount=250" +
                " listStartOffset=0>"
        private const val FILTER_PROPERTIES_TO_STRING =
            "<PbapApplicationParameters properties=[PROPERTY_VERSION PROPERTY_FN PROPERTY_N]" +
                " format=1 maxListCount=250 listStartOffset=0>"
        private const val FILTER_INDIVIDUAL_PROPERTIES_TO_STRING =
            "<PbapApplicationParameters properties=[PROPERTY_VERSION PROPERTY_FN PROPERTY_N" +
                " PROPERTY_PHOTO PROPERTY_ADR PROPERTY_TEL PROPERTY_EMAIL PROPERTY_NICKNAME]" +
                " format=1 maxListCount=250 listStartOffset=0>"
        private const val FORMAT_21_TO_STRING =
            "<PbapApplicationParameters properties=PROPERTIES_ALL format=0 maxListCount=250" +
                " listStartOffset=0>"
    }
}
