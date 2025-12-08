/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may
 * obtain a copy of the License at
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

import android.bluetooth.IAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

private const val TAG = "BluetoothComponent"

/** Stores the package and component name of the Bluetooth application */
class BluetoothComponent(context: Context) {
    /**
     * The package name of the Bluetooth application.
     *
     * Can be either `com.android.bluetooth` or `com.android.google.bluetooth`
     */
    val packageName: String

    /** The component name of the Bluetooth AdapterService */
    val componentName: ComponentName

    init {
        val pm = context.packageManager
        val intent = Intent(IAdapter::class.java.name)

        // The Bluetooth UID is shared by a very limited number of packages.
        // We can optimize the resolveService lookup by only considering those packages.
        val bluetoothPackages = pm.getPackagesForUid(Process.BLUETOOTH_UID)
        if (bluetoothPackages != null && bluetoothPackages.size == 1) {
            intent.setPackage(bluetoothPackages[0])
        }

        val result = pm.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY)
        if (result == null) {
            throw IllegalStateException("No service can handle intent $intent")
        }

        val serviceInfo = result.serviceInfo
        packageName = serviceInfo.packageName
        componentName = ComponentName(serviceInfo.packageName, serviceInfo.name)
        Log.i(TAG, "Found Bluetooth component: $componentName")
    }

    companion object {
        val ADAPTER_CLASS = "com.android.bluetooth.btservice.AdapterService"
    }
}
