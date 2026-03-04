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

package com.android.bluetooth.pbap

import android.content.Context
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.R
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.kotlin.whenever

/** Test cases for [BluetoothPbapConfig]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothPbapConfigTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var context: Context
    @Mock private lateinit var resources: Resources

    @Before
    fun setUp() {
        doReturn(resources).whenever(context).resources
    }

    @Test
    fun testInit_whenUseProfileForOwnerVcardIsTrue() {
        doReturn(true).whenever(resources).getBoolean(R.bool.pbap_use_profile_for_owner_vcard)

        BluetoothPbapConfig.init(context)
        assertThat(BluetoothPbapConfig.useProfileForOwnerVcard()).isTrue()
    }

    @Test
    fun testInit_whenUseProfileForOwnerVcardIsFalse() {
        doReturn(false).whenever(resources).getBoolean(R.bool.pbap_use_profile_for_owner_vcard)

        BluetoothPbapConfig.init(context)
        assertThat(BluetoothPbapConfig.useProfileForOwnerVcard()).isFalse()
    }

    @Test
    fun testInit_whenUseProfileForOwnerVcardThrowsException() {
        doThrow(RuntimeException())
            .whenever(resources)
            .getBoolean(R.bool.pbap_use_profile_for_owner_vcard)

        BluetoothPbapConfig.init(context)
        // Test should not crash
    }

    @Test
    fun testInit_whenIncludePhotosInVcardIsTrue() {
        doReturn(true).whenever(resources).getBoolean(R.bool.pbap_include_photos_in_vcard)

        BluetoothPbapConfig.init(context)
        assertThat(BluetoothPbapConfig.includePhotosInVcard()).isTrue()
    }

    @Test
    fun testInit_whenIncludePhotosInVcardIsFalse() {
        doReturn(false).whenever(resources).getBoolean(R.bool.pbap_include_photos_in_vcard)

        BluetoothPbapConfig.init(context)
        assertThat(BluetoothPbapConfig.includePhotosInVcard()).isFalse()
    }

    @Test
    fun testInit_whenIncludePhotosInVcardThrowsException() {
        doThrow(RuntimeException())
            .whenever(resources)
            .getBoolean(R.bool.pbap_include_photos_in_vcard)

        BluetoothPbapConfig.init(context)
        // Test should not crash
    }
}
