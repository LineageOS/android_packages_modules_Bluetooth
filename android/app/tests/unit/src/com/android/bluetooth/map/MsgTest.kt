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
import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [Msg]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MsgTest {

    @Test
    fun constructor() {
        val msg = BluetoothMapContentObserver.Msg(TEST_ID, TEST_FOLDER_ID, TEST_READ_FLAG)
        assertThat(msg.id).isEqualTo(TEST_ID)
        assertThat(msg.folderId).isEqualTo(TEST_FOLDER_ID)
        assertThat(msg.flagRead).isEqualTo(TEST_READ_FLAG)
    }

    @Test
    fun hashCode_returnsExpectedResult() {
        val msg = BluetoothMapContentObserver.Msg(TEST_ID, TEST_FOLDER_ID, TEST_READ_FLAG)
        val expected = 31 + (TEST_ID xor (TEST_ID ushr 32)).toInt()
        assertThat(msg.hashCode()).isEqualTo(expected)
    }

    @Test
    fun equals() {
        val idOne = 1L
        val idTwo = 2L
        val msg = BluetoothMapContentObserver.Msg(idOne, TEST_FOLDER_ID, TEST_READ_FLAG)
        val msgWithSameId = BluetoothMapContentObserver.Msg(idOne, TEST_FOLDER_ID, TEST_READ_FLAG)
        val msgWithDifferentId =
            BluetoothMapContentObserver.Msg(idTwo, TEST_FOLDER_ID, TEST_READ_FLAG)
        val msgOfDifferentClass = "msg_of_different_class"

        EqualsTester()
            .addEqualityGroup(msg, msg, msgWithSameId)
            .addEqualityGroup(msgWithDifferentId, msgWithDifferentId)
            .addEqualityGroup(msgOfDifferentClass)
            .testEquals()
    }

    companion object {
        private const val TEST_ID = 1L
        private const val TEST_FOLDER_ID = 1L
        private const val TEST_READ_FLAG = 1
    }
}
