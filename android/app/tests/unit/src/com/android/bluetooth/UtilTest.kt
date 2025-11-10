/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.Util.checkProfileAvailable
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.ProfileService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val TAG = "UtilTest"

/** Test cases for [Util]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class UtilTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val device = getTestDevice(1)

    @Test
    fun checkProfileAvailable() {
        assertThat(null.checkProfileAvailable(TAG)).isFalse()

        val mockProfile = mock<ProfileService>()
        doReturn(false).whenever(mockProfile).isAvailable
        assertThat(mockProfile.checkProfileAvailable(TAG)).isFalse()

        doReturn(true).whenever(mockProfile).isAvailable
        assertThat(mockProfile.checkProfileAvailable(TAG)).isTrue()
    }

    @Test
    fun remoteDeviceIsWatch() {
        assertThat(Util.remoteDeviceIsWatch(adapterService, device)).isFalse()

        doReturn(BluetoothClass.Device.WEARABLE_WRIST_WATCH)
            .whenever(adapterService)
            .getRemoteClass(device)
        assertThat(Util.remoteDeviceIsWatch(adapterService, device)).isTrue()

        // IS a watch (Metadata matches, even if CoD doesn't)
        doReturn(BluetoothClass.Device.WEARABLE_UNCATEGORIZED)
            .whenever(adapterService)
            .getRemoteClass(device)
        doReturn(BluetoothDevice.DEVICE_TYPE_WATCH.toByteArray())
            .whenever(adapterService)
            .getMetadata(device, BluetoothDevice.METADATA_DEVICE_TYPE)

        assertThat(Util.remoteDeviceIsWatch(adapterService, device)).isTrue()
    }
}
