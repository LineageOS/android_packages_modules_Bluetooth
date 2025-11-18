/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.util.Log
import androidx.core.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import io.grpc.Deadline
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pandora.HostProto.ScanRequest
import pandora.HostProto.ScanningResponse

private const val TAG = "LeAdvertisingTest"

/** Test cases for [AdvertiseManager]. */
@RunWith(AndroidJUnit4::class)
class LeAdvertisingTest {
    @get:Rule(order = 0) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 1) val bumble = PandoraDevice()

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun advertisingSet() {
        val addressPair = startAdvertising().join()
        var response = scanWithBumble(addressPair)

        Log.i(TAG, "scan response: $response")
        assertThat(response).isNotNull()

        response = scanWithBumble(addressPair)
        Log.i(TAG, "second scan response: $response")
        assertThat(response).isNotNull()
    }

    private fun startAdvertising(): CompletableFuture<Pair<String, Int>> {
        val future = CompletableFuture<Pair<String, Int>>()

        // Start advertising
        val parameters =
            AdvertisingSetParameters.Builder()
                .setOwnAddressType(AdvertisingSetParameters.ADDRESS_TYPE_RANDOM)
                .build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val advertisingSetCallback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet,
                    txPower: Int,
                    status: Int,
                ) {
                    Log.i(TAG, "onAdvertisingSetStarted  txPower:$txPower status:$status")
                    advertisingSet.enableAdvertising(true, TIMEOUT_ADVERTISING_MS.toInt(), 0)
                }

                override fun onOwnAddressRead(
                    advertisingSet: AdvertisingSet,
                    addressType: Int,
                    address: String,
                ) {
                    Log.i(TAG, "onOwnAddressRead  addressType:$addressType address:$address")
                    future.complete(Pair(address, addressType))
                }

                override fun onAdvertisingEnabled(
                    advertisingSet: AdvertisingSet,
                    enabled: Boolean,
                    status: Int,
                ) {
                    Log.i(TAG, "onAdvertisingEnabled  enabled:$enabled status:$status")
                    advertisingSet.getOwnAddress()
                }
            }
        leAdvertiser.startAdvertisingSet(
            parameters,
            advertiseData,
            scanResponse,
            null,
            null,
            0,
            0,
            advertisingSetCallback,
        )

        return future
    }

    private fun scanWithBumble(addressPair: Pair<String, Int>): ScanningResponse? {
        Log.d(TAG, "scanWithBumble")
        val address = addressPair.first
        val addressType = addressPair.second

        val responseObserver = StreamObserverSpliterator<ScanRequest, ScanningResponse>()
        val deadline = Deadline.after(TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS)
        bumble
            .host()
            .withDeadline(deadline)
            .scan(ScanRequest.newBuilder().build(), responseObserver)
        val responseObserverIterator = responseObserver.iterator()
        while (true) {
            val scanningResponse = responseObserverIterator.next()
            val scanningResponseBytes =
                if (addressType == AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC)
                    scanningResponse.public
                else scanningResponse.random
            val addr = scanningResponseBytes.toAddressString()
            if (addr == address) {
                responseObserver.cancel("Cancelling scan request")
                return scanningResponse
            }
        }
    }

    companion object {
        private const val TIMEOUT_ADVERTISING_MS = 1000L
    }
}
