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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [BluetoothMapContract]. */
@RunWith(AndroidJUnit4::class)
class BluetoothMapContractTest {

    @Test
    fun testBuildAccountUri() {
        val expectedUriString = "content://$TEST_AUTHORITY/${BluetoothMapContract.TABLE_ACCOUNT}"

        val result = BluetoothMapContract.buildAccountUri(TEST_AUTHORITY)
        assertThat(result.toString()).isEqualTo(expectedUriString)
    }

    @Test
    fun testBuildFolderUri() {
        val expectedUriString =
            "content://$TEST_AUTHORITY/$ACCOUNT_ID/${BluetoothMapContract.TABLE_FOLDER}"

        val result = BluetoothMapContract.buildFolderUri(TEST_AUTHORITY, ACCOUNT_ID)
        assertThat(result.toString()).isEqualTo(expectedUriString)
    }

    companion object {
        private const val TEST_AUTHORITY = "com.test"
        private const val ACCOUNT_ID = "test_account_id"
    }
}
