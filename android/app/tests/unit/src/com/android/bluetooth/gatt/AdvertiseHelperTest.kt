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

package com.android.bluetooth.gatt

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.TransportDiscoveryData
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [AdvertiseHelper]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvertiseHelperTest {

    @Test
    fun advertiseDataToBytes() {
        val emptyBytes = AdvertiseHelper.advertiseDataToBytes(null, "")
        assertThat(emptyBytes.size).isEqualTo(0)

        val manufacturerId = 1
        val manufacturerData = byteArrayOf(0x30, 0x31, 0x32, 0x34)
        val serviceData = byteArrayOf(0x10, 0x12, 0x14)
        val transportDiscoveryData = byteArrayOf(0x40, 0x44, 0x48)
        val advertiseData =
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addManufacturerData(manufacturerId, manufacturerData)
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(ParcelUuid(UUID.randomUUID()))
                .addServiceData(ParcelUuid(UUID.randomUUID()), serviceData)
                .addServiceSolicitationUuid(ParcelUuid(UUID.randomUUID()))
                .addTransportDiscoveryData(TransportDiscoveryData(transportDiscoveryData))
                .build()
        val deviceName = "TestDeviceName"
        val expectedAdvDataBytesLength = 86

        val advDataBytes = AdvertiseHelper.advertiseDataToBytes(advertiseData, deviceName)
        assertThat(advDataBytes.size).isEqualTo(expectedAdvDataBytesLength)

        val deviceNameLong = "TestDeviceNameLongTestDeviceName"
        val expectedAdvDataBytesLongNameLength = 98

        val advDataBytesLongName =
            AdvertiseHelper.advertiseDataToBytes(advertiseData, deviceNameLong)
        assertThat(advDataBytesLongName.size).isEqualTo(expectedAdvDataBytesLongNameLength)
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkLength_withGT255_throwsException() {
        AdvertiseHelper.check_length(0X00, 256)
    }
}
