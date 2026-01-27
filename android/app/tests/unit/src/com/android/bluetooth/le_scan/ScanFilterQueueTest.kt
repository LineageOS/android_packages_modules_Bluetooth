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

package com.android.bluetooth.le_scan

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothUuid
import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.primitives.Bytes
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [ScanFilterQueue]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanFilterQueueTest {
    @Test
    fun scanFilterQueueParams() {
        val queue = ScanFilterQueue()

        val address = "address"
        val type: Byte = 1
        val irk = byteArrayOf(0x02)
        queue.addDeviceAddress(address, type, irk)

        val uuid = UUID.randomUUID()
        queue.addUuid(uuid)

        val uuidMask = UUID.randomUUID()
        queue.addUuid(uuid, uuidMask)

        val solicitUuid = UUID.randomUUID()
        val solicitUuidMask = UUID.randomUUID()
        queue.addSolicitUuid(solicitUuid, solicitUuidMask)

        val name = "name"
        queue.addName(name)

        val company = 2
        val data = byteArrayOf(0x04)
        queue.addManufacturerData(company, data)

        val companyMask = 2
        val dataMask = byteArrayOf(0x05)
        queue.addManufacturerData(company, companyMask, data, dataMask)

        val serviceData = byteArrayOf(0x06)
        val serviceDataMask = byteArrayOf(0x08)
        queue.addServiceData(serviceData, serviceDataMask)

        val adType = 3
        val adData = byteArrayOf(0x10)
        val adDataMask = byteArrayOf(0x12)
        queue.addAdvertisingDataType(adType, adData, adDataMask)

        val entries = queue.toArray()
        val entriesLength = 9
        assertThat(entries.size).isEqualTo(entriesLength)

        for (entry in entries) {
            when (entry.type.toInt()) {
                ScanFilterQueue.TYPE_DEVICE_ADDRESS -> {
                    assertThat(entry.address).isEqualTo(address)
                    assertThat(entry.addr_type).isEqualTo(type)
                    assertThat(entry.irk).isEqualTo(irk)
                }
                ScanFilterQueue.TYPE_SERVICE_DATA_CHANGED -> assertThat(entry).isNotNull()
                ScanFilterQueue.TYPE_SERVICE_UUID -> assertThat(entry.uuid).isEqualTo(uuid)
                ScanFilterQueue.TYPE_SOLICIT_UUID -> {
                    assertThat(entry.uuid).isEqualTo(solicitUuid)
                    assertThat(entry.uuid_mask).isEqualTo(solicitUuidMask)
                }
                ScanFilterQueue.TYPE_LOCAL_NAME -> assertThat(entry.name).isEqualTo(name)
                ScanFilterQueue.TYPE_MANUFACTURER_DATA -> {
                    assertThat(entry.company).isEqualTo(company)
                    assertThat(entry.data).isEqualTo(data)
                }
                ScanFilterQueue.TYPE_SERVICE_DATA -> {
                    assertThat(entry.data).isEqualTo(serviceData)
                    assertThat(entry.data_mask).isEqualTo(serviceDataMask)
                }
                ScanFilterQueue.TYPE_ADVERTISING_DATA_TYPE -> {
                    assertThat(entry.ad_type).isEqualTo(adType)
                    assertThat(entry.data).isEqualTo(adData)
                    assertThat(entry.data_mask).isEqualTo(adDataMask)
                }
            }
        }
    }

    @Test
    fun popEmpty() {
        val queue = ScanFilterQueue()
        val entry = queue.pop()
        assertThat(entry).isNull()
    }

    @Test
    fun popFromQueue() {
        val queue = ScanFilterQueue()

        val serviceData = byteArrayOf(0x02)
        val serviceDataMask = byteArrayOf(0x04)
        queue.addServiceData(serviceData, serviceDataMask)

        val entry = queue.pop()
        assertThat(entry.data).isEqualTo(serviceData)
        assertThat(entry.data_mask).isEqualTo(serviceDataMask)
    }

    @Test
    fun checkFeatureSelection() {
        val queue = ScanFilterQueue()

        val serviceData = byteArrayOf(0x02)
        val serviceDataMask = byteArrayOf(0x04)
        queue.addServiceData(serviceData, serviceDataMask)

        val feature = 1 shl ScanFilterQueue.TYPE_SERVICE_DATA
        assertThat(queue.getFeatureSelection()).isEqualTo(feature)
    }

    @Test
    fun convertQueueToArray() {
        val queue = ScanFilterQueue()

        val serviceData = byteArrayOf(0x02)
        val serviceDataMask = byteArrayOf(0x04)
        queue.addServiceData(serviceData, serviceDataMask)

        val entries = queue.toArray()
        val entriesLength = 1
        assertThat(entries.size).isEqualTo(entriesLength)

        val entry = entries[0]
        assertThat(entry.data).isEqualTo(serviceData)
        assertThat(entry.data_mask).isEqualTo(serviceDataMask)
    }

    @Test
    fun queueAddScanFilter() {
        val queue = ScanFilterQueue()

        val name = "name"
        val deviceAddress = "00:11:22:33:FF:EE"
        val serviceUuid = ParcelUuid.fromString(UUID.randomUUID().toString())
        val serviceSolicitationUuid = ParcelUuid.fromString(UUID.randomUUID().toString())
        val manufacturerId = 0
        val manufacturerData = ByteArray(0)
        val serviceDataUuid = ParcelUuid.fromString(UUID.randomUUID().toString())
        val serviceData = ByteArray(0)
        val advertisingDataType = 1

        val filter =
            ScanFilter.Builder()
                .setDeviceName(name)
                .setDeviceAddress(deviceAddress)
                .setServiceUuid(serviceUuid)
                .setServiceSolicitationUuid(serviceSolicitationUuid)
                .setManufacturerData(manufacturerId, manufacturerData)
                .setServiceData(serviceDataUuid, serviceData)
                .setAdvertisingDataType(advertisingDataType)
                .build()
        queue.addScanFilter(filter)

        val numOfEntries = 7
        assertThat(queue.toArray().size).isEqualTo(numOfEntries)
    }

    @Test
    fun addScanFilter_withAddressTypeAndIrk_propagatesToEntry() {
        // This test verifies that when a ScanFilter with a specific address type and IRK is added,
        // the ScanFilterQueue correctly creates an Entry with those properties. This is important
        // for ensuring that address resolution information is correctly passed down.
        val queue = ScanFilterQueue()
        val deviceAddress = "00:11:22:33:FF:EE"
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val irk =
            byteArrayOf(
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
            )

        // Mock a ScanFilter since creating one with addressType and IRK requires SystemApi.
        val mockFilter = mock<ScanFilter>()
        doReturn(deviceAddress).whenever(mockFilter).deviceAddress
        doReturn(addressType).whenever(mockFilter).addressType
        doReturn(irk).whenever(mockFilter).irk

        // Add the mocked filter to the queue.
        queue.addScanFilter(mockFilter)

        // The queue should contain one entry for the device address.
        val entries = queue.toArray()
        assertThat(entries.size).isEqualTo(1)

        // Verify the entry's properties match the mocked filter's properties.
        val entry = entries[0]
        assertThat(entry.type).isEqualTo(ScanFilterQueue.TYPE_DEVICE_ADDRESS)
        assertThat(entry.address).isEqualTo(deviceAddress)
        assertThat(entry.addr_type).isEqualTo(addressType.toByte())
        assertThat(entry.irk).isEqualTo(irk)
    }

    @Test
    fun serviceDataFilterNoMask1() {
        val filter =
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(TEST_UUID_STRING), TEST_SERVICE_DATA)
                .build()
        testServiceDataFilter(filter, false)
    }

    @Test
    fun serviceDataFilterWithFullMask() {
        val filter =
            ScanFilter.Builder()
                .setServiceData(
                    ParcelUuid.fromString(TEST_UUID_STRING),
                    TEST_SERVICE_DATA,
                    FULL_SERVICE_DATA_MASK,
                )
                .build()
        testServiceDataFilter(filter, false)
    }

    @Test
    fun serviceDataFilterWithPartialMask() {
        val filter =
            ScanFilter.Builder()
                .setServiceData(
                    ParcelUuid.fromString(TEST_UUID_STRING),
                    TEST_SERVICE_DATA,
                    PARTIAL_SERVICE_DATA_MASK,
                )
                .build()
        testServiceDataFilter(filter, true)
    }

    private fun testServiceDataFilter(filter: ScanFilter, partialServiceDataMatchResult: Boolean) {
        val queue = ScanFilterQueue()
        queue.addScanFilter(filter)
        val entry = queue.pop()
        assertThat(entry.type).isEqualTo(ScanFilterQueue.TYPE_SERVICE_DATA)
        assertThat(entry.data)
            .isEqualTo(
                Bytes.concat(
                    BluetoothUuid.uuidToBytes(ParcelUuid.fromString(TEST_UUID_STRING)),
                    TEST_SERVICE_DATA,
                )
            )
        assertThat(
                serviceDataMatches(
                    entry.data,
                    Bytes.concat(
                        BluetoothUuid.uuidToBytes(ParcelUuid.fromString(TEST_UUID_STRING)),
                        TEST_SERVICE_DATA,
                    ),
                    entry.data_mask,
                )
            )
            .isTrue()
        assertThat(
                serviceDataMatches(
                    entry.data,
                    Bytes.concat(
                        BluetoothUuid.uuidToBytes(ParcelUuid.fromString(UNMATCHED_UUID_STRING)),
                        TEST_SERVICE_DATA,
                    ),
                    entry.data_mask,
                )
            )
            .isFalse()
        assertThat(
                serviceDataMatches(
                    entry.data,
                    Bytes.concat(
                        BluetoothUuid.uuidToBytes(ParcelUuid.fromString(TEST_UUID_STRING)),
                        UNMATCHED_SERVICE_DATA,
                    ),
                    entry.data_mask,
                )
            )
            .isFalse()
        assertThat(
                serviceDataMatches(
                    entry.data,
                    Bytes.concat(
                        BluetoothUuid.uuidToBytes(ParcelUuid.fromString(TEST_UUID_STRING)),
                        PARTIALLY_MATCHED_SERVICE_DATA,
                    ),
                    entry.data_mask,
                )
            )
            .isEqualTo(partialServiceDataMatchResult)
    }

    private fun serviceDataMatches(
        filterData: ByteArray,
        resultData: ByteArray,
        mask: ByteArray,
    ): Boolean {
        if (filterData.size > resultData.size || filterData.size != mask.size) {
            return false
        }
        for (i in filterData.indices) {
            if (
                (filterData[i].toInt() and mask[i].toInt()) !=
                    (resultData[i].toInt() and mask[i].toInt())
            ) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val TEST_UUID_STRING = "00001805-0000-1000-8000-00805f9b34fb"
        private const val UNMATCHED_UUID_STRING = "00001815-0000-1000-8000-00805f9b34fb"
        private val TEST_SERVICE_DATA = byteArrayOf(0x18.toByte(), 0x0F.toByte())
        private val PARTIALLY_MATCHED_SERVICE_DATA =
            byteArrayOf(0x08.toByte(), 0x0F.toByte(), 0xAB.toByte(), 0xCD.toByte())
        private val UNMATCHED_SERVICE_DATA = byteArrayOf(0x08.toByte(), 0x0E.toByte())
        private val PARTIAL_SERVICE_DATA_MASK = byteArrayOf(0x00.toByte(), 0xFF.toByte())
        private val FULL_SERVICE_DATA_MASK = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    }
}
