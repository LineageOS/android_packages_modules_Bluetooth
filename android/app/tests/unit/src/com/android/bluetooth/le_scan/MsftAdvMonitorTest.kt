/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [MsftAdvMonitor]. */
@RunWith(AndroidJUnit4::class)
class MsftAdvMonitorTest {
    @Test
    fun testFastPairScanFilter() {
        val filter =
            ScanFilter.Builder()
                .setServiceData(FAST_PAIR_SERVICE_DATA_UUID, FAST_PAIR_SERVICE_DATA)
                .build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_PATTERNS)
        assertThat(monitor.patterns).hasLength(1)
        val pattern = monitor.patterns[0]
        assertThat(pattern.ad_type)
            .isEqualTo(0x16.toByte()) // Bluetooth Core Spec Part A, Section 1
        assertThat(pattern.start_byte).isEqualTo(FILTER_PATTERN_START_POSITION)
        assertThat(pattern.pattern)
            .isEqualTo(
                byteArrayOf(
                    0x2c.toByte(),
                    0xfe.toByte(),
                    0xfc.toByte(),
                    0x12.toByte(),
                    0x8e.toByte(),
                )
            )
    }

    @Test
    fun testServiceDataWithMaskScanFilter() {
        val mask = byteArrayOf(1, 0, 0)
        val filter =
            ScanFilter.Builder()
                .setServiceData(FAST_PAIR_SERVICE_DATA_UUID, FAST_PAIR_SERVICE_DATA, mask)
                .build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_PATTERNS)
        assertThat(monitor.patterns).hasLength(1)
        val pattern = monitor.patterns[0]
        assertThat(pattern.ad_type)
            .isEqualTo(0x16.toByte()) // Bluetooth Core Spec Part A, Section 1
        assertThat(pattern.start_byte).isEqualTo(FILTER_PATTERN_START_POSITION)
        // Since there is a mask, the service data should not be appended, only the UUID.
        assertThat(pattern.pattern).isEqualTo(byteArrayOf(0x2c.toByte(), 0xfe.toByte()))
    }

    @Test
    fun testDeviceNameScanFilter() {
        val deviceName = "testDevice"
        val filter = ScanFilter.Builder().setDeviceName(deviceName).build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_PATTERNS)
        assertThat(monitor.patterns).hasLength(1)
        val mPattern = monitor.patterns[0]
        assertThat(mPattern.ad_type)
            .isEqualTo(0x09.toByte()) // Assigned Numbers Document, Section 2.3
        assertThat(mPattern.start_byte).isEqualTo(FILTER_PATTERN_START_POSITION)
        assertThat(mPattern.pattern).isEqualTo(deviceName.toByteArray())
    }

    @Test
    fun testUuid16ScanFilter() {
        val filter = ScanFilter.Builder().setServiceUuid(UUID_16).build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_UUID)
        val mUuid = monitor.uuid
        assertThat(mUuid.uuid).hasLength(2)
        assertThat(mUuid.uuid).isEqualTo(byteArrayOf(0x34.toByte(), 0x12.toByte()))
    }

    @Test
    fun testUuid32ScanFilter() {
        val filter = ScanFilter.Builder().setServiceUuid(UUID_32).build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_UUID)
        val mUuid = monitor.uuid
        assertThat(mUuid.uuid).hasLength(4)
        assertThat(mUuid.uuid)
            .isEqualTo(byteArrayOf(0x34.toByte(), 0x12.toByte(), 0xCD.toByte(), 0xAB.toByte()))
    }

    @Test
    fun testUuid128ScanFilter() {
        val filter = ScanFilter.Builder().setServiceUuid(UUID_128).build()
        val monitor = MsftAdvMonitor(filter)

        assertMonitorConstants(monitor, CONDITION_TYPE_UUID)
        val mUuid = monitor.uuid
        assertThat(mUuid.uuid).hasLength(16)
        assertThat(mUuid.uuid[0]).isEqualTo(0xFF.toByte())
        assertThat(mUuid.uuid[15]).isEqualTo(0x11.toByte())
    }

    private fun assertMonitorConstants(monitor: MsftAdvMonitor, conditionType: Byte) =
        with(monitor.monitor) {
            assertThat(rssi_threshold_high).isEqualTo(RSSI_THRESHOLD_HIGH)
            assertThat(rssi_threshold_low).isEqualTo(RSSI_THRESHOLD_LOW)
            assertThat(rssi_threshold_low_time_interval).isEqualTo(RSSI_THRESHOLD_LOW_TIME_INTERVAL)
            assertThat(rssi_sampling_period).isEqualTo(RSSI_SAMPLING_PERIOD)
            assertThat(condition_type).isEqualTo(conditionType)
        }

    companion object {
        // Hardcoded values taken from CrOS defaults
        private const val RSSI_THRESHOLD_HIGH = 0xBF.toByte() // 191
        private const val RSSI_THRESHOLD_LOW = 0xB0.toByte() // 176
        private const val RSSI_THRESHOLD_LOW_TIME_INTERVAL = 0x28.toByte() // 40s
        private const val RSSI_SAMPLING_PERIOD = 0x05.toByte() // 500ms
        private const val CONDITION_TYPE_PATTERNS = 0x01.toByte()
        private const val CONDITION_TYPE_UUID = 0x02.toByte()
        private const val FILTER_PATTERN_START_POSITION = 0x00.toByte()

        private val UUID_16 = ParcelUuid.fromString("00001234-0000-1000-8000-00805F9B34FB")
        private val UUID_32 = ParcelUuid.fromString("ABCD1234-0000-1000-8000-00805F9B34FB")
        private val UUID_128 = ParcelUuid.fromString("11223344-5566-7788-9900-AABBCCDDEEFF")

        // Retrieved from real Fastpair filter data
        private const val FAST_PAIR_UUID = "0000fe2c-0000-1000-8000-00805f9b34fb"
        private val FAST_PAIR_SERVICE_DATA_UUID = ParcelUuid.fromString(FAST_PAIR_UUID)
        private val FAST_PAIR_SERVICE_DATA =
            byteArrayOf(0xfc.toByte(), 0x12.toByte(), 0x8e.toByte())
    }
}
