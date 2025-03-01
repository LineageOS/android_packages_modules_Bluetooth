/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;
import com.google.protobuf.ByteString;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link AdvtFilterOnFoundOnLostInfo}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdvtFilterOnFoundOnLostInfoTest {

    @Rule public Expect expect = Expect.create();

    @Test
    public void advtFilterOnFoundOnLostInfoParams() {
        int clientIf = 0;
        int advPacketLen = 1;
        ByteString advPacket = ByteString.copyFrom(new byte[] {0x02});
        int scanResponseLen = 3;
        ByteString scanResponse = ByteString.copyFrom(new byte[] {0x04});
        int filtIndex = 5;
        int advState = 6;
        int advInfoPresent = 7;
        String address = "00:11:22:33:FF:EE";
        int addressType = 8;
        int txPower = 9;
        int rssiValue = 10;
        int timeStamp = 11;
        byte[] resultByteArray = new byte[] {2, 4};

        AdvtFilterOnFoundOnLostInfo advtFilterOnFoundOnLostInfo =
                new AdvtFilterOnFoundOnLostInfo(
                        clientIf,
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
                        timeStamp);

        expect.that(advtFilterOnFoundOnLostInfo.clientIf()).isEqualTo(clientIf);
        expect.that(advtFilterOnFoundOnLostInfo.advPacketLen()).isEqualTo(advPacketLen);
        expect.that(advtFilterOnFoundOnLostInfo.advPacket()).isEqualTo(advPacket);
        expect.that(advtFilterOnFoundOnLostInfo.scanResponseLen()).isEqualTo(scanResponseLen);
        expect.that(advtFilterOnFoundOnLostInfo.scanResponse()).isEqualTo(scanResponse);
        expect.that(advtFilterOnFoundOnLostInfo.filtIndex()).isEqualTo(filtIndex);
        expect.that(advtFilterOnFoundOnLostInfo.advState()).isEqualTo(advState);
        expect.that(advtFilterOnFoundOnLostInfo.advInfoPresent()).isEqualTo(advInfoPresent);
        expect.that(advtFilterOnFoundOnLostInfo.address()).isEqualTo(address);
        expect.that(advtFilterOnFoundOnLostInfo.addressType()).isEqualTo(addressType);
        expect.that(advtFilterOnFoundOnLostInfo.txPower()).isEqualTo(txPower);
        expect.that(advtFilterOnFoundOnLostInfo.rssiValue()).isEqualTo(rssiValue);
        expect.that(advtFilterOnFoundOnLostInfo.timeStamp()).isEqualTo(timeStamp);
        expect.that(advtFilterOnFoundOnLostInfo.getResult()).isEqualTo(resultByteArray);
    }
}
