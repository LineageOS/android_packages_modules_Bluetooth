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

package com.android.server.bluetooth

import android.os.ServiceManager

private const val BLUETOOTH_HCI_INTERFACE = "android.hardware.bluetooth.IBluetoothHci"
private const val HCI_DEFAULT_INSTANCE_NAME = "default"
private const val TAG = "BluetoothHciInstance"

class BluetoothHciInstance {
    private val hciInstances: Array<String> =
        ServiceManager.getDeclaredInstances(BLUETOOTH_HCI_INTERFACE)

    init {
        Log.i(
            TAG,
            "Service manager declared bluetooth hci instances: ${hciInstances.contentToString()}",
        )
    }

    fun getInstance(): String {
        // For now return only the first one, or default if none to adhere to legacy behavior
        return if (hciInstances.isEmpty()) HCI_DEFAULT_INSTANCE_NAME else hciInstances[0]
    }
}
