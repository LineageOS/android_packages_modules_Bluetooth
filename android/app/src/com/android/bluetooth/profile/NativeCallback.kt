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

package com.android.bluetooth.profile

import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService

/**
 * Base class for native callback interfaces.
 *
 * This class provides common functionality, such as converting a Bluetooth device address (as a
 * `String` or `ByteArray`) into a `BluetoothDevice` object using the [AdapterService].
 */
abstract class NativeCallback(internal val adapterService: AdapterService) {

    fun getDevice(address: String) = getDevice(Util.getBytesFromAddress(address))

    fun getDevice(address: ByteArray) = adapterService.getDeviceFromByte(address)
}
