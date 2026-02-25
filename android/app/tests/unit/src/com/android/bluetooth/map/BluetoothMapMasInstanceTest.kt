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

import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.mockBluetoothManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/** Test cases for [BluetoothMapMasInstance]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothMapMasInstanceTest {
    @get:Rule val mockitoRule = MockitoRule()

    private lateinit var accountItem: BluetoothMapAccountItem

    @Before
    fun setUp() {
        val colorDrawable = mock<ColorDrawable>()
        accountItem =
            BluetoothMapAccountItem.create(
                TEST_ID,
                TEST_NAME,
                TEST_PACKAGE_NAME,
                TEST_PROVIDER_AUTHORITY,
                colorDrawable,
                TEST_TYPE,
                TEST_UCI,
                TEST_UCI_PREFIX,
            )
    }

    @Test
    fun toString_returnsInfo() {
        val adapterService = mock<AdapterService>()
        val mapService = mock<BluetoothMapService>()
        adapterService.mockBluetoothManager()

        val instance =
            BluetoothMapMasInstance(
                adapterService,
                mapService,
                accountItem,
                TEST_MAS_ID,
                TEST_ENABLE_SMS_MMS,
            )

        val expected =
            "MasId: $TEST_MAS_ID Uri:${accountItem.mBase_uri} SMS/MMS:$TEST_ENABLE_SMS_MMS"
        assertThat(instance.toString()).isEqualTo(expected)
    }

    companion object {
        private const val TEST_MAS_ID = 1
        private const val TEST_ENABLE_SMS_MMS = true
        private const val TEST_NAME = "test_name"
        private const val TEST_PACKAGE_NAME = "test.package.name"
        private const val TEST_ID = "1111"
        private const val TEST_PROVIDER_AUTHORITY = "test.project.provider"
        private val TEST_TYPE = BluetoothMapUtils.TYPE.EMAIL
        private const val TEST_UCI = "uci"
        private const val TEST_UCI_PREFIX = "uci_prefix"
    }
}
