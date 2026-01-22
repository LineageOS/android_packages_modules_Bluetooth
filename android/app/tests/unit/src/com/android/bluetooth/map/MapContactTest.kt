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
import com.android.bluetooth.SignedLongLong
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [MapContact]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MapContactTest {

    @Test
    fun constructor() {
        val contact = MapContact(TEST_NON_ZERO_ID, TEST_NAME)
        assertThat(contact.id).isEqualTo(TEST_NON_ZERO_ID)
        assertThat(contact.name).isEqualTo(TEST_NAME)
    }

    @Test
    fun getXBtUidString_withZeroId() {
        val contact = MapContact(TEST_ZERO_ID, TEST_NAME)
        assertThat(contact.getXBtUidString()).isNull()
    }

    @Test
    fun getXBtUidString_withNonZeroId() {
        val contact = MapContact(TEST_NON_ZERO_ID, TEST_NAME)
        assertThat(contact.getXBtUidString())
            .isEqualTo(BluetoothMapUtils.getLongLongAsString(TEST_NON_ZERO_ID, 0))
    }

    @Test
    fun getXBtUid_withZeroId() {
        val contact = MapContact(TEST_ZERO_ID, TEST_NAME)
        assertThat(contact.getXBtUid()).isNull()
    }

    @Test
    fun getXBtUid_withNonZeroId() {
        val contact = MapContact(TEST_NON_ZERO_ID, TEST_NAME)
        assertThat(contact.getXBtUid()).isEqualTo(SignedLongLong(TEST_NON_ZERO_ID, 0))
    }

    @Test
    fun toString_returnsName() {
        val contact = MapContact(TEST_NON_ZERO_ID, TEST_NAME)
        assertThat(contact.toString()).isEqualTo(TEST_NAME)
    }

    companion object {
        private const val TEST_NON_ZERO_ID = 1L
        private const val TEST_ZERO_ID = 0L
        private const val TEST_NAME = "test_name"
    }
}
