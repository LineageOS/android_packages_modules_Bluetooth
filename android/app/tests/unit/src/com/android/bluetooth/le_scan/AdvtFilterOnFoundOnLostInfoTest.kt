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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Expect
import com.google.protobuf.ByteString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [AdvtFilterOnFoundOnLostInfo]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvtFilterOnFoundOnLostInfoTest {
    @get:Rule val expect = Expect.create()

    @Test
    fun advtFilterOnFoundOnLostInfoParams() {
        val scannerId = 0
        val advPacketLen = 1
        val advPacket = ByteString.copyFrom(byteArrayOf(0x02))
        val scanResponseLen = 3
        val scanResponse = ByteString.copyFrom(byteArrayOf(0x04))
        val filtIndex = 5
        val advState = 6
        val advInfoPresent = 7
        val address = "00:11:22:33:FF:EE"
        val addressType = 8
        val txPower = 9
        val rssiValue = 10
        val timeStamp = 11
        val resultByteArray = byteArrayOf(2, 4)

        val advtFilterOnFoundOnLostInfo =
            AdvtFilterOnFoundOnLostInfo(
                scannerId,
                advPacketLen,
                advPacket,
                scanResponseLen,
                scanResponse,
                filtIndex,
                advState,
                advInfoPresent,
                address,
                addressType,
                txPower,
                rssiValue,
                timeStamp,
            )

        expect.that(advtFilterOnFoundOnLostInfo.scannerId).isEqualTo(scannerId)
        expect.that(advtFilterOnFoundOnLostInfo.advPacketLen).isEqualTo(advPacketLen)
        expect.that(advtFilterOnFoundOnLostInfo.advPacket).isEqualTo(advPacket)
        expect.that(advtFilterOnFoundOnLostInfo.scanResponseLen).isEqualTo(scanResponseLen)
        expect.that(advtFilterOnFoundOnLostInfo.scanResponse).isEqualTo(scanResponse)
        expect.that(advtFilterOnFoundOnLostInfo.filtIndex).isEqualTo(filtIndex)
        expect.that(advtFilterOnFoundOnLostInfo.advState).isEqualTo(advState)
        expect.that(advtFilterOnFoundOnLostInfo.advInfoPresent).isEqualTo(advInfoPresent)
        expect.that(advtFilterOnFoundOnLostInfo.address).isEqualTo(address)
        expect.that(advtFilterOnFoundOnLostInfo.addressType).isEqualTo(addressType)
        expect.that(advtFilterOnFoundOnLostInfo.txPower).isEqualTo(txPower)
        expect.that(advtFilterOnFoundOnLostInfo.rssiValue).isEqualTo(rssiValue)
        expect.that(advtFilterOnFoundOnLostInfo.timeStamp).isEqualTo(timeStamp)
        expect.that(advtFilterOnFoundOnLostInfo.getResult()).isEqualTo(resultByteArray)
    }
}
