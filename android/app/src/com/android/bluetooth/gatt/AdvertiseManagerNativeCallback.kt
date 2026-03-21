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

package com.android.bluetooth.gatt

import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

class AdvertiseManagerNativeCallback(
    adapterService: AdapterService,
    private val manager: AdvertiseManager,
) : NativeCallback(adapterService) {

    fun onAdvertisingSetStarted(regId: Int, advertiserId: Int, txPower: Int, status: Int) {
        doOnAdvertiseThread { onAdvertisingSetStarted(regId, advertiserId, txPower, status) }
    }

    fun onOwnAddressRead(advertiserId: Int, addressType: Int, address: String?) {
        doOnAdvertiseThread { onOwnAddressRead(advertiserId, addressType, address) }
    }

    fun onAdvertisingEnabled(advertiserId: Int, enable: Boolean, status: Int) {
        doOnAdvertiseThread { onAdvertisingEnabled(advertiserId, enable, status) }
    }

    fun onAdvertisingDataSet(advertiserId: Int, status: Int) {
        doOnAdvertiseThread { onAdvertisingDataSet(advertiserId, status) }
    }

    fun onScanResponseDataSet(advertiserId: Int, status: Int) {
        doOnAdvertiseThread { onScanResponseDataSet(advertiserId, status) }
    }

    fun onAdvertisingParametersUpdated(advertiserId: Int, txPower: Int, status: Int) {
        doOnAdvertiseThread { onAdvertisingParametersUpdated(advertiserId, txPower, status) }
    }

    fun onPeriodicAdvertisingParametersUpdated(advertiserId: Int, status: Int) {
        doOnAdvertiseThread { onPeriodicAdvertisingParametersUpdated(advertiserId, status) }
    }

    fun onPeriodicAdvertisingDataSet(advertiserId: Int, status: Int) {
        doOnAdvertiseThread { onPeriodicAdvertisingDataSet(advertiserId, status) }
    }

    fun onPeriodicAdvertisingEnabled(advertiserId: Int, enable: Boolean, status: Int) {
        doOnAdvertiseThread { onPeriodicAdvertisingEnabled(advertiserId, enable, status) }
    }

    private fun doOnAdvertiseThread(block: AdvertiseManager.() -> Unit) =
        manager.doOnAdvertiseThread {
            manager.block()
        }
}
