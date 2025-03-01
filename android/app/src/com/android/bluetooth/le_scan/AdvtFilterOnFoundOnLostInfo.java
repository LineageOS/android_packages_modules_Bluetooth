/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.bluetooth.BluetoothDevice;

import com.google.protobuf.ByteString;

record AdvtFilterOnFoundOnLostInfo(
        int clientIf,
        int advPacketLen,
        ByteString advPacket,
        int scanResponseLen,
        ByteString scanResponse,
        int filtIndex,
        int advState,
        int advInfoPresent,
        String address,
        @BluetoothDevice.AddressType int addressType,
        int txPower,
        int rssiValue,
        int timeStamp) {

    public byte[] getResult() {
        if (scanResponse == null) {
            return advPacket.toByteArray();
        }
        return advPacket.concat(scanResponse).toByteArray();
    }
}
