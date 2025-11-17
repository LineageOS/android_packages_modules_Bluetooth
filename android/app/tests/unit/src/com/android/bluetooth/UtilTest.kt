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
import android.bluetooth.BluetoothStatusCodes
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

    @Test
    fun hciToAndroidDisconnectReason() {
        val params =
            mapOf(
                0x00 to BluetoothStatusCodes.ERROR_UNKNOWN,
                0x1F to BluetoothStatusCodes.ERROR_UNKNOWN,
                0xff to BluetoothStatusCodes.ERROR_UNKNOWN,
                0x01 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x02 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x03 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x2A to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x32 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x35 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL,
                0x04 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x08 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x10 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x22 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x3C to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x3E to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT,
                0x05 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x06 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x0E to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x17 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x18 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x25 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x26 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x29 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x2F to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x38 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY,
                0x07 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x09 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x0A to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x0C to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x0D to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x43 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED,
                0x0B to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS,
                0x0F to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SYSTEM_POLICY,
                0x12 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS,
                0x13 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST,
                0x15 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST,
                0x16 to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL_REQUEST,
                0x1A to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE,
                0x3B to BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS,
            )

        params.forEach { (hci, expected) ->
            assertThat(Util.hciToAndroidDisconnectReason(hci)).isEqualTo(expected)
        }

        assertThat(Util.hciToAndroidDisconnectReason(0x9999))
            .isEqualTo(BluetoothStatusCodes.ERROR_UNKNOWN)
    }
}
