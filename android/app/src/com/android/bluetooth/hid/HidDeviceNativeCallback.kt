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

package com.android.bluetooth.hid

import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

class HidDeviceNativeCallback(
    adapterService: AdapterService,
    private val service: HidDeviceService,
) : NativeCallback(adapterService) {

    @Synchronized
    fun onApplicationStateChanged(address: ByteArray?, registered: Boolean) {
        val device = if (address != null) getDevice(address) else null
        service.onApplicationStateChangedFromNative(device, registered)
    }

    @Synchronized
    fun onConnectStateChanged(address: ByteArray, state: Int) =
        service.onConnectStateChangedFromNative(getDevice(address), state)

    @Synchronized
    fun onGetReport(type: Byte, id: Byte, bufferSize: Short) =
        service.onGetReportFromNative(type, id, bufferSize)

    @Synchronized
    fun onSetReport(reportType: Byte, reportId: Byte, data: ByteArray) =
        service.onSetReportFromNative(reportType, reportId, data)

    @Synchronized fun onSetProtocol(protocol: Byte) = service.onSetProtocolFromNative(protocol)

    @Synchronized
    fun onInterruptData(reportId: Byte, data: ByteArray) =
        service.onInterruptDataFromNative(reportId, data)

    @Synchronized fun onVirtualCableUnplug() = service.onVirtualCableUnplugFromNative()
}
