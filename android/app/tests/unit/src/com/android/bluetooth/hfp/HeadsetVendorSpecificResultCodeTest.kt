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

package com.android.bluetooth.hfp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [HeadsetVendorSpecificResultCode]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HeadsetVendorSpecificResultCodeTest {
    private val device = getTestDevice(78)

    @Test
    fun constructor() {
        val code = HeadsetVendorSpecificResultCode(device, TEST_COMMAND, TEST_ARG)
        assertThat(code.mDevice).isEqualTo(device)
        assertThat(code.mCommand).isEqualTo(TEST_COMMAND)
        assertThat(code.mArg).isEqualTo(TEST_ARG)
    }

    @Test
    fun buildString() {
        val code = HeadsetVendorSpecificResultCode(device, TEST_COMMAND, TEST_ARG)
        val builder = StringBuilder()
        code.buildString(builder)
        val expectedString =
            "${code.javaClass.simpleName}[device=$device, command=$TEST_COMMAND, arg=$TEST_ARG]"
        assertThat(builder.toString()).isEqualTo(expectedString)
    }

    companion object {
        private const val TEST_COMMAND = "test_command"
        private const val TEST_ARG = "test_arg"
    }
}
