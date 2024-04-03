/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.compat.CompatChanges
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.SIGNATURE_MATCH
import android.os.Process.NFC_UID
import android.os.Process.ROOT_UID
import android.os.Process.SHELL_UID
import android.os.Process.SYSTEM_UID
import android.os.UserHandle
import android.os.UserManager
import android.permission.PermissionManager
import com.android.bluetooth.flags.Flags
import com.android.modules.utils.build.SdkLevel.isAtLeastU
import com.android.server.bluetooth.ChangeIds.RESTRICT_ENABLE_DISABLE

private const val TAG = "PermissionChecker"

class PermissionChecker(
    private val context: Context,
    private val userManager: UserManager,
    private val packageManager: PackageManager,
    private val permissionManager: PermissionManager,
    private val appOpsManager: AppOpsManager,
    private val attributionSource: AttributionSource,
) {

    class BluetoothPermissionException(message: String? = null, cause: Throwable? = null) :
        Exception(message, cause)

    fun enableAllowed(uid: Int, source: AttributionSource, foregroundRequired: Boolean) =
        userCanToggle(uid, source, "enable", foregroundRequired)

    fun disableAllowed(uid: Int, source: AttributionSource, foregroundRequired: Boolean) =
        userCanToggle(uid, source, "disable", foregroundRequired)

    fun factoryResetAllowed(source: AttributionSource) =
        enforceConnectPermission(source, "factoryReset")

    fun enforcePrivileged(uid: Int) = context.enforcePermission(BLUETOOTH_PRIVILEGED, -1, uid, null)

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////// PRIVATE METHODS ///////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private fun userCanToggle(
        uid: Int,
        source: AttributionSource,
        apiName: String,
        foregroundRequired: Boolean,
    ) {

        enforceBluetoothRestriction()

        val callingAppId = UserHandle.getAppId(uid)
        if (arrayOf(SYSTEM_UID, NFC_UID, SHELL_UID, ROOT_UID).contains(callingAppId)) {
            // special uid can always toggle
            // TODO: b/280890575 - remove process bypass
            return
        }

        source.packageName?.let { checkPackage(uid, it) } // null package belongs to any uid

        if (foregroundRequired) {
            enforceCallerIsForegroundUser(uid)
            enforceCompatChange(uid, source.packageName)
        }

        enforceConnectPermission(source, apiName)
    }

    private fun enforceBluetoothRestriction() {
        val isBluetoothAllowed =
            if (Flags.userRestrictionRefactor()) {
                BluetoothRestriction.isBluetoothAllowed
            } else {
                !userManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_BLUETOOTH,
                    UserHandle.SYSTEM,
                )
            }
        if (!isBluetoothAllowed) {
            throw BluetoothPermissionException("Bluetooth is not allowed")
        }
    }

    /** Check if the packageName belongs to uid */
    private fun checkPackage(uid: Int, packageName: String) {
        // getPackageUidAsUser is only available starting API level 34 == U
        if (!isAtLeastU()) {
            try {
                @Suppress("DEPRECATION") // Suppress for compatibility with platform < 34
                appOpsManager.checkPackage(uid, packageName)
            } catch (e: SecurityException) {
                throw SecurityException("$packageName does not belong to $uid: " + e.message)
            }
            return
        }
        try {
            // TODO: b/280890575 - Make sure this behave like deprecated appOpsManager.checkPackage
            val packageUid =
                packageManager.getPackageUidAsUser(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                    uid,
                )
            if (packageUid != uid) {
                throw SecurityException("$packageName does not belong to $uid (vs $packageUid)")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "checkPackage($uid, $packageName)", e)
            throw SecurityException(e.message)
        }
    }

    private fun enforceCallerIsForegroundUser(uid: Int) {
        val callingUser = UserHandle.getUserHandleForUid(uid)

        // TODO: b/280890575 - replace with the current user the service is switched to
        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())
        val parentUser = userManager.getProfileParent(callingUser)

        val callingAppId = UserHandle.getAppId(uid)

        // TODO: b/280890575 - Is this systemUi exception still needed ??
        if (
            callingAppId ==
                packageManager.getPackageUid(
                    "com.android.systemui",
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
                )
        ) {
            Log.e(TAG, "Detected systemUi package call, will call go threw ??")
        }

        if (callingUser != foregroundUser && parentUser != foregroundUser) {
            throw BluetoothPermissionException(
                "Not allowed for non-active and non system user." +
                    " callingUser=${callingUser}" +
                    " parentUser=${parentUser}" +
                    " foregroundUser=${foregroundUser}" +
                    " callingAppId=${callingAppId}"
            )
        }
    }

    private fun enforceConnectPermission(clientSource: AttributionSource, apiName: String) {
        val perm = android.Manifest.permission.BLUETOOTH_CONNECT
        val source = AttributionSource.Builder(attributionSource).setNext(clientSource).build()
        val msg = "${apiName} enforce ${perm}. But permission is missing for source=${source}"

        when (permissionManager.checkPermissionForDataDeliveryFromDataSource(perm, source, msg)) {
            PermissionManager.PERMISSION_GRANTED -> {} /* nothing to do, permission granted */
            PermissionManager.PERMISSION_HARD_DENIED -> throw SecurityException(msg)
            PermissionManager.PERMISSION_SOFT_DENIED -> throw BluetoothPermissionException(msg)
        }
    }

    private fun enforceLocalMacAddressPermission(uid: Int, apiName: String) {
        val perm = android.Manifest.permission.LOCAL_MAC_ADDRESS

        val msg = "${apiName} enforce ${perm}. But permission is missing"
        when (context.checkPermission(perm, -1, uid)) {
            PackageManager.PERMISSION_GRANTED -> {} /* nothing to do, permission granted */
            PackageManager.PERMISSION_DENIED -> throw BluetoothPermissionException(msg)
        // TODO(b/280890575): Throws a SecurityException instead
        }
    }

    private fun enforceCompatChange(uid: Int, packageName: String?) {
        if (packageName != null && isExcludedFromCompatChange(uid, packageName)) {
            return
        }

        if (CompatChanges.isChangeEnabled(RESTRICT_ENABLE_DISABLE, uid)) {
            throw BluetoothPermissionException("Caller does not match restriction criteria")
        }
    }

    private fun isExcludedFromCompatChange(uid: Int, packageName: String): Boolean {
        return isPrivileged(uid) ||
            isSystem(uid, packageName) ||
            isDeviceOwner(uid, packageName) ||
            isProfileOwner(uid, packageName)
    }

    private fun isPrivileged(uid: Int): Boolean {
        return (context.checkPermission(BLUETOOTH_PRIVILEGED, -1, uid) ==
            PackageManager.PERMISSION_GRANTED) ||
            (packageManager.checkSignatures(uid, SYSTEM_UID) == SIGNATURE_MATCH)
    }

    private fun isSystem(uid: Int, packageName: String): Boolean {
        val callingUser = UserHandle.getUserHandleForUid(uid)
        val info = packageManager.getApplicationInfoAsUser(packageName, 0, callingUser)
        val SYSTEM_APP = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (info.flags and SYSTEM_APP) != 0
    }

    private fun isDeviceOwner(uid: Int, packageName: String): Boolean {
        // DevicePolicyManager is started after Bluetooth and cannot be passed in constructor
        val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
        if (devicePolicyManager == null) {
            Log.w(TAG, "isDeviceOwner: Error retrieving DevicePolicyManager service")
            return false
        }
        val deviceOwnerUser = devicePolicyManager.deviceOwnerUser ?: return false
        val deviceOwnerComponent = devicePolicyManager.deviceOwnerComponentOnAnyUser ?: return false

        return deviceOwnerUser.equals(UserHandle.getUserHandleForUid(uid)) &&
            deviceOwnerComponent.getPackageName().equals(packageName)
    }

    private fun isProfileOwner(uid: Int, packageName: String?): Boolean {
        val userContext =
            try {
                context.createPackageContextAsUser(
                    context.packageName,
                    0,
                    UserHandle.getUserHandleForUid(uid),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Unknown package name")
                return false
            }
        val devicePolicyManager = userContext.getSystemService(DevicePolicyManager::class.java)
        if (devicePolicyManager == null) {
            Log.w(TAG, "isProfileOwner: Error retrieving DevicePolicyManager service")
            return false
        }
        return devicePolicyManager.isProfileOwnerApp(packageName)
    }
}
