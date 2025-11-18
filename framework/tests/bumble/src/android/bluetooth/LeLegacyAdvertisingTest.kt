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

package android.bluetooth

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeLegacyAdvertisingTest {
    @get:Rule(order = 0) val permissionRule = AdoptShellPermissionsRule()

    @Test
    fun setAdvertisingDataOver31Bytes() {
        // Set legacy scan mode
        val params =
            AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()

        val advertiseData =
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.randomUUID())).build()

        val future = CompletableFuture<Int>()

        val callback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet,
                    txPower: Int,
                    status: Int,
                ) {
                    // Should be greater than 31
                    val advertisingDataLengthWhichExceedsLimit = 50
                    advertisingSet.setAdvertisingData(
                        createAdvertiseData(advertisingDataLengthWhichExceedsLimit)
                    )
                }

                override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet, status: Int) {
                    future.complete(status)
                }
            }

        try {
            leAdvertiser.startAdvertisingSet(params, advertiseData, null, null, null, callback)
            future.completeOnTimeout(null, TIMEOUT_MS, TimeUnit.MILLISECONDS).join()

            val setAdvertingDataResult = future.get()
            assertThat(setAdvertingDataResult).isNotNull()
            assertThat(setAdvertingDataResult)
                .isEqualTo(AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        } finally {
            leAdvertiser.stopAdvertisingSet(callback)
        }
    }

    @Test
    fun setScanResponseDataOver31Bytes() {
        // Set legacy scan mode
        val params =
            AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()

        val advertiseData =
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.randomUUID())).build()

        val future = CompletableFuture<Int>()

        val callback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet,
                    txPower: Int,
                    status: Int,
                ) {
                    // Should be greater than 31
                    val scanResponseDataLengthWhichExceedsLimit = 50
                    advertisingSet.setScanResponseData(
                        createAdvertiseData(scanResponseDataLengthWhichExceedsLimit)
                    )
                }

                override fun onScanResponseDataSet(advertisingSet: AdvertisingSet, status: Int) {
                    future.complete(status)
                }
            }

        try {
            leAdvertiser.startAdvertisingSet(params, advertiseData, null, null, null, callback)
            future.completeOnTimeout(null, TIMEOUT_MS, TimeUnit.MILLISECONDS).join()

            val setScanResponseResult = future.get()
            assertThat(setScanResponseResult).isNotNull()
            assertThat(setScanResponseResult)
                .isEqualTo(AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        } finally {
            leAdvertiser.stopAdvertisingSet(callback)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 1_000L

        private fun createAdvertiseData(length: Int): AdvertiseData? {
            if (length <= 4) {
                return null
            }

            // Create an arbitrary manufacturer specific data
            val manufacturerId = BluetoothAssignedNumbers.GOOGLE
            val manufacturerSpecificData = ByteArray(length - 4)
            for (i in manufacturerSpecificData.indices) {
                manufacturerSpecificData[i] = i.toByte()
            }

            return AdvertiseData.Builder()
                .addManufacturerData(manufacturerId, manufacturerSpecificData)
                .build()
        }
    }
}
