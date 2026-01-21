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

package com.android.bluetooth.avrcpcontroller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [RequestGetImageProperties]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class RequestGetImagePropertiesTest {

    @Test
    fun constructor() {
        val requestGetImageProperties = RequestGetImageProperties(TEST_IMAGE_HANDLE)
        assertThat(requestGetImageProperties.imageHandle).isEqualTo(TEST_IMAGE_HANDLE)
    }

    @Test
    fun getType() {
        val requestGetImageProperties = RequestGetImageProperties(TEST_IMAGE_HANDLE)
        assertThat(requestGetImageProperties.type).isEqualTo(BipRequest.TYPE_GET_IMAGE_PROPERTIES)
    }

    companion object {
        private const val TEST_IMAGE_HANDLE = "test_image_handle"
    }
}
