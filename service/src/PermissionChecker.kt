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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.LOCAL_MAC_ADDRESS
import android.annotation.RequiresPermission
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.compat.CompatChanges
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_ANY_USER
import android.content.pm.PackageManager.MATCH_SYSTEM_ONLY
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.PackageManager.SIGNATURE_MATCH
import android.os.Process.NFC_UID
import android.os.Process.ROOT_UID
import android.os.Process.SHELL_UID
import android.os.Process.SYSTEM_UID
import android.os.UserHandle
import android.os.UserManager
import android.permission.PermissionManager
import com.android.server.bluetooth.ChangeIds.RESTRICT_ENABLE_DISABLE

private const val TAG = "PermissionChecker"

class PermissionChecker(
    private val context: Context,
    private val userManager: UserManager,
    private val packageManager: PackageManager,
    private val permissionManager: PermissionManager,
    private val attributionSource: AttributionSource,
) {

    // We need to allow SystemUi to bypass some 'foreground user check'
    // TODO: remove this hack and validate secondary user can still toggle via quick settings
    private val systemUiUid: Int =
        try {
            val uid =
                context.packageManager.getPackageUid(
                    "com.android.systemui",
                    PackageInfoFlags.of(MATCH_SYSTEM_ONLY.toLong()),
                )
            Log.d(TAG, "SystemUi's UID successfully detected: $uid")
            uid
        } catch (e: NameNotFoundException) {
            Log.w(TAG, "Unable to resolve SystemUI's UID.")
            -1
        }

    // Throw an exception that will be catch prior to return to caller
    class BluetoothPermissionException(message: String? = null, cause: Throwable? = null) :
        Exception(message, cause)

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun enableAllowed(source: AttributionSource, foregroundRequired: Boolean) =
        userCanToggle(source, "enable", foregroundRequired)

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun disableAllowed(source: AttributionSource, foregroundRequired: Boolean) =
        userCanToggle(source, "disable", foregroundRequired)

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun factoryResetAllowed(source: AttributionSource) =
        enforceConnectPermission(source, "factoryReset")

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, LOCAL_MAC_ADDRESS])
    fun getAddressAllowed(source: AttributionSource) {
        enforceConnectPermission(source, "getAddress")
        if (source.uid != SYSTEM_UID) enforceCallerIsForegroundUser(source.uid)
        enforceLocalMacAddressPermission(source.uid, "getAddress")
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun setNameAllowed(source: AttributionSource) {
        enforceConnectPermission(source, "setName")
        if (source.uid != SYSTEM_UID) enforceCallerIsForegroundUser(source.uid)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun getNameAllowed(source: AttributionSource) {
        enforceConnectPermission(source, "getName")
        if (source.uid != SYSTEM_UID) enforceCallerIsForegroundUser(source.uid)
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    fun enforcePrivileged(uid: Int) = context.enforcePermission(BLUETOOTH_PRIVILEGED, -1, uid, null)

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////// PRIVATE METHODS ///////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun userCanToggle(
        source: AttributionSource,
        apiName: String,
        foregroundRequired: Boolean,
    ) {
        enforceBluetoothRestriction()

        val callingAppId = UserHandle.getAppId(source.uid)
        if (arrayOf(SYSTEM_UID, NFC_UID, SHELL_UID, ROOT_UID).contains(callingAppId)) {
            // special uid can always toggle
            // TODO: b/280890575 - remove process bypass
            return
        }

        val packageName =
            source.packageName
                ?: throw BluetoothPermissionException("Null package name from ${source.uid}")
        checkPackageName(callingAppId, packageName)

        if (foregroundRequired) {
            enforceCallerIsForegroundUser(source.uid)
            enforceCompatChange(source)
        }

        enforceConnectPermission(source, apiName)
    }

    private fun enforceBluetoothRestriction() {
        if (!BluetoothRestriction.isBluetoothAllowed) {
            throw BluetoothPermissionException("Bluetooth is not allowed")
        }
    }

    /** Check if the packageName belongs to the calling app */
    private fun checkPackageName(appId: Int, name: String) {
        val trustedAppId =
            UserHandle.getAppId(
                try {
                    packageManager.getPackageUid(name, MATCH_ANY_USER)
                } catch (e: NameNotFoundException) {
                    Log.w(TAG, "checkPackageName($appId, $name): Failed", e)
                    throw SecurityException(e.message)
                }
            )
        if (trustedAppId != appId) {
            throw SecurityException("$name does not belong to $appId (expected $trustedAppId)")
        }
    }

    private fun enforceCallerIsForegroundUser(uid: Int) {
        val callingUser = UserHandle.getUserHandleForUid(uid)

        // TODO: b/280890575 - replace with the current user the service is switched to
        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())
        val parentUser = userManager.getProfileParent(callingUser)

        val callingAppId = UserHandle.getAppId(uid)

        if (
            callingUser != foregroundUser &&
                parentUser != foregroundUser &&
                callingAppId != systemUiUid // TODO remove foreground bypass
        ) {
            throw BluetoothPermissionException(
                "Not allowed for non-active and non system user." +
                    " callingUser=$callingUser" +
                    " parentUser=$parentUser" +
                    " foregroundUser=$foregroundUser" +
                    " callingAppId=$callingAppId"
            )
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun enforceConnectPermission(clientSource: AttributionSource, apiName: String) {
        val perm = BLUETOOTH_CONNECT
        val source = AttributionSource.Builder(attributionSource).setNext(clientSource).build()
        val msg = "$apiName enforce $perm. But permission is missing for source=$source"
        when (permissionManager.checkPermissionForDataDeliveryFromDataSource(perm, source, msg)) {
            PermissionManager.PERMISSION_GRANTED -> {} /* nothing to do, permission granted */
            PermissionManager.PERMISSION_HARD_DENIED -> throw SecurityException(msg)
            PermissionManager.PERMISSION_SOFT_DENIED -> throw BluetoothPermissionException(msg)
        }
    }

    @RequiresPermission(LOCAL_MAC_ADDRESS)
    private fun enforceLocalMacAddressPermission(uid: Int, apiName: String) {
        val perm = LOCAL_MAC_ADDRESS
        val msg = "$apiName enforce $perm. But permission is missing"
        when (context.checkPermission(perm, -1, uid)) {
            PackageManager.PERMISSION_GRANTED -> {} /* nothing to do, permission granted */
            PackageManager.PERMISSION_DENIED -> throw BluetoothPermissionException(msg)
        // TODO(b/280890575): Throws a SecurityException instead
        }
    }

    private fun enforceCompatChange(source: AttributionSource) {
        if (isExcludedFromCompatChange(source)) {
            return
        }

        if (CompatChanges.isChangeEnabled(RESTRICT_ENABLE_DISABLE, source.uid)) {
            throw BluetoothPermissionException("Caller does not match restriction criteria")
        }
    }

    private fun isExcludedFromCompatChange(source: AttributionSource): Boolean {
        return isPrivileged(source.uid) ||
            isSystem(source) ||
            isDeviceOwner(source) ||
            isProfileOwner(source)
    }

    @Suppress("IncorrectRequiresPermissionPropagation") // No permission enforcement
    private fun isPrivileged(uid: Int): Boolean {
        return (context.checkPermission(BLUETOOTH_PRIVILEGED, -1, uid) ==
            PackageManager.PERMISSION_GRANTED) ||
            (packageManager.checkSignatures(uid, SYSTEM_UID) == SIGNATURE_MATCH)
    }

    private fun isSystem(source: AttributionSource): Boolean {
        val callingUser = UserHandle.getUserHandleForUid(source.uid)
        val info = packageManager.getApplicationInfoAsUser(source.packageName!!, 0, callingUser)
        val SYSTEM_APP = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (info.flags and SYSTEM_APP) != 0
    }

    private fun isDeviceOwner(source: AttributionSource): Boolean {
        // DevicePolicyManager is started after Bluetooth and cannot be passed in constructor
        val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
        if (devicePolicyManager == null) {
            Log.w(TAG, "isDeviceOwner: Error retrieving DevicePolicyManager service")
            return false
        }
        val deviceOwnerUser = devicePolicyManager.deviceOwnerUser ?: return false
        val deviceOwnerComponent = devicePolicyManager.deviceOwnerComponentOnAnyUser ?: return false

        return deviceOwnerUser.equals(UserHandle.getUserHandleForUid(source.uid)) &&
            deviceOwnerComponent.packageName.equals(source.packageName)
    }

    private fun isProfileOwner(source: AttributionSource): Boolean {
        val userContext =
            try {
                context.createPackageContextAsUser(
                    context.packageName,
                    0,
                    UserHandle.getUserHandleForUid(source.uid),
                )
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "Unknown package name")
                return false
            }
        val devicePolicyManager = userContext.getSystemService(DevicePolicyManager::class.java)
        if (devicePolicyManager == null) {
            Log.w(TAG, "isProfileOwner: Error retrieving DevicePolicyManager service")
            return false
        }
        return devicePolicyManager.isProfileOwnerApp(source.packageName)
    }
}
