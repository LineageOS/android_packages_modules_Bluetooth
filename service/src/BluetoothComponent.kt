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
import android.content.pm.ServiceInfo
import android.os.Process
import com.android.bluetooth.flags.Flags

private const val TAG = "BluetoothComponent"

/** Stores the package and component name of the Bluetooth application */
class BluetoothComponent(context: Context) {
    /**
     * The package name of the Bluetooth application.
     *
     * Can be either `com.android.bluetooth` or `com.google.android.bluetooth`
     */
    val packageName: String

    /** The component name of the Bluetooth AdapterService */
    val componentName: ComponentName

    init {
        val serviceInfo = validateServiceInfo(context)

        packageName = serviceInfo.packageName
        componentName = ComponentName(serviceInfo.packageName, serviceInfo.name)

        if (android.bluetooth.platform.flags.Flags.strictConfigurationInSystemServer()) {
            if (Flags.validateBluetoothNameInPlatformConfig()) {
                validateDeviceConfiguration(context)
            }
        }
        Log.i(TAG, "Successfully found Bluetooth component: $componentName")
    }

    /**
     * Deduct the Bluetooth service info based on the installed application
     *
     * Bluetooth apk name is different when packaged as a mainline module. To manage the Bluetooth
     * lifecycle, we need to know which package contains the service that expose [IAdapter]
     *
     * A missing service indicate that [PackageManager.hasSystemFeature(FEATURE_BLUETOOTH)] is true
     * but that there are no Bluetooth apk installed. This is an invalid configuration.
     *
     * To disable Bluetooth for some user, see [UserManager.setUserRestriction]. This can also be
     * configured for work-profile using `bluetoothDisabled` option.
     *
     * Hanlding of the user restriction can be found at [BluetoothRestriction]
     *
     * @throws IllegalStateException If no service can handle the IAdapter intent
     */
    @Throws(IllegalStateException::class)
    private fun validateServiceInfo(context: Context): ServiceInfo {
        val pm = context.packageManager
        val intent = Intent(IAdapter::class.java.name)

        // The Bluetooth UID is shared by a very limited number of packages.
        // We can optimize the resolveService lookup by only considering those packages.
        val bluetoothPackages = pm.getPackagesForUid(Process.BLUETOOTH_UID)
        if (bluetoothPackages != null && bluetoothPackages.size == 1) {
            intent.setPackage(bluetoothPackages[0])
        }

        val resolveInfo = pm.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY)
        if (resolveInfo == null) {
            val msg =
                """
            Conflicting system configuration detected.
                - Device is starting with `hasSystemFeature(FEATURE_BLUETOOTH) == true`.
                - No services installed on the device can handle: $intent.

            Resolution (One of):
                - Make sure the Bluetooth APK is installed
                - Change device configuration to not support the FEATURE_BLUETOOTH.

            Note:
                To restrict Bluetooth usage from work profile, use [bluetoothDisabled] option."""
                    .trimIndent()
            throw IllegalStateException("FATAL: $msg")
        }

        return resolveInfo.serviceInfo
    }

    /**
     * Validate detected Bluetooth package name match the declared configuration.
     *
     * Bluetooth apk name is different when packaged as a mainline module. Some components of the
     * system (permission, telephony) will rely on a global resource to know the name of the
     * Bluetooth package and grant some permissions.
     *
     * If the config is missing, permission may be missing and Bluetooth may not work. If there is a
     * mis-match, an exception will be thrown and prevent boot.
     *
     * To help debugging, booting in safe mode will never throw an exception.
     *
     * The name is most likely to be
     * - `com.android.bluetooth` on a non-mainline device (may be customized by OEM)
     * - `com.google.android.bluetooth` on a mainline device
     *
     * @throws IllegalStateException If the device configuration is invalid
     */
    @Throws(IllegalStateException::class)
    private fun validateDeviceConfiguration(ctx: Context) {
        val resId = ctx.resources.getIdentifier("config_systemBluetoothStack", "string", "android")
        if (resId == 0) {
            Log.w(TAG, "No Device configuration. Bluetooth is expected not to work properly")
            return
        }
        val configPackageName = ctx.resources.getString(resId)
        if (configPackageName == packageName) {
            return
        }
        val msg =
            """
        The system config does not match the installed Bluetooth APK. Bluetooth will not work.
            - Installed APK: [$packageName]
            - System Config: config_systemBluetoothStack=[$configPackageName]
            These values must match to grant correct permissions.

        Resolution:
            Override `config_systemBluetoothStack` to match the installed APK package name.

        Workarounds:
             Reboot into Safe Mode to temporarily bypass this check (Bluetooth will be disabled)."""
                .trimIndent()
        // Downgrade fatal exception to a log when the device boot in Safe Mode
        if (ctx.packageManager.isSafeMode()) {
            Log.e(TAG, "Bypass FATAL due to SafeMode. Original message: $msg")
        } else {
            throw IllegalStateException("FATAL: $msg")
        }
    }
}
