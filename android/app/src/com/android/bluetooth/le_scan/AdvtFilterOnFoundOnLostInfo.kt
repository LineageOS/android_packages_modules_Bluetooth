/*
 * Copyright (C) 2026 The Android Open Source Project
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
import com.google.protobuf.ByteString

// All values of this class are accessed from native; see com_android_bluetooth_scan.cpp
data class AdvtFilterOnFoundOnLostInfo(
    val scannerId: Int,
    val advPacketLen: Int,
    val advPacket: ByteString,
    val scanResponseLen: Int,
    val scanResponse: ByteString?,
    val filtIndex: Int,
    val advState: Int,
    val advInfoPresent: Int,
    val address: String,
    @param:BluetoothDevice.AddressType val addressType: Int,
    val txPower: Int,
    val rssiValue: Int,
    val timeStamp: Int,
) {
    fun getResult(): ByteArray =
        scanResponse?.let { advPacket.concat(it).toByteArray() } ?: advPacket.toByteArray()
}
