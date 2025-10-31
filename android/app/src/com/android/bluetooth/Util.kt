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

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.btservice.AdapterService

object Util {
    const val BT_PREFIX = "Bluetooth"

    @JvmStatic
    fun AdapterService.appNameOrUnknown(uid: Int) =
        packageManager.getNameForUid(uid) ?: "Unknown App (UID: $uid)"

    @JvmStatic
    fun addressTypeToString(addressType: Int) =
        when (addressType) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "Public "
            BluetoothDevice.ADDRESS_TYPE_RANDOM -> "Random "
            else -> "Unknown"
        }

    @JvmStatic
    fun deviceTypeToString(deviceType: Int) =
        when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_UNKNOWN -> " ???? "
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "BR/EDR"
            BluetoothDevice.DEVICE_TYPE_LE -> "  LE  "
            BluetoothDevice.DEVICE_TYPE_DUAL -> " DUAL "
            else -> "Invalid device type: $deviceType"
        }

    @JvmInline
    internal value class Transport(val value: Int) {
        override fun toString() = transportToString(value)
    }

    @JvmStatic
    fun transportToString(transport: Int) =
        when (transport) {
            BluetoothDevice.TRANSPORT_AUTO -> "AUTO"
            BluetoothDevice.TRANSPORT_BREDR -> "BR/EDR"
            BluetoothDevice.TRANSPORT_LE -> "LE"
            else -> "Unknown transport ($transport)"
        }
}
