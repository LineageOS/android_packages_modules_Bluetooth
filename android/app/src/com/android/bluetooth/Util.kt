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

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NETWORK_SETTINGS
import android.Manifest.permission.NETWORK_SETUP_WIZARD
import android.Manifest.permission.RADIO_SCAN_WITHOUT_LOCATION
import android.Manifest.permission.RENOUNCE_PERMISSIONS
import android.Manifest.permission.WRITE_SMS
import android.annotation.PermissionMethod
import android.annotation.PermissionName
import android.annotation.RequiresPermission
import android.app.BroadcastOptions
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.METADATA_DEVICE_TYPE
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothUtils
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_PERMISSIONS
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerExemptionManager
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.permission.PermissionManager
import android.permission.PermissionManager.PERMISSION_GRANTED
import android.permission.PermissionManager.PERMISSION_HARD_DENIED
import android.provider.DeviceConfig
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.ProfileService
import com.android.modules.utils.build.SdkLevel

private const val TAG = Util.BT_PREFIX + "Util"

object Util {
    const val BT_PREFIX = "Bluetooth"

    private const val KEY_TEMP_ALLOW_LIST_DURATION_MS = "temp_allow_list_duration_ms"
    private const val DEFAULT_TEMP_ALLOW_LIST_DURATION_MS = 20_000L

    /**
     * Check if we are running in `BluetoothInstrumentationTest` context by trying to load
     * `com.android.bluetooth.TestUtils`. If we are not in Instrumentation test mode, this class
     * should not be found. If `TestUtils` is removed in the future, another test class in
     * BluetoothInstrumentationTest should be used instead
     */
    @JvmStatic
    val isInstrumentationTestMode: Boolean by lazy {
        runCatching { Class.forName("com.android.bluetooth.TestUtils") }.isSuccess
    }

    /**
     * Throws [IllegalStateException] if we are not in BluetoothInstrumentationTest. Useful for
     * ensuring certain methods only get called in BluetoothInstrumentationTest
     */
    @JvmStatic
    fun enforceInstrumentationTestMode() {
        check(isInstrumentationTestMode) { "Not in BluetoothInstrumentationTest" }
    }

    @JvmStatic
    fun ProfileService?.checkProfileAvailable(tag: String): Boolean {
        if (this == null) {
            Log.w(TAG, "$tag - Not present")
            return false
        }
        if (!this.isAvailable) {
            Log.w(TAG, "$tag - Not available")
            return false
        }
        return true
    }

    /**
     * Get uid/pid string in a binder call
     *
     * @return "uid/pid=xxxx/yyyy"
     */
    @JvmStatic fun getUidPidString() = "uid/pid=${Binder.getCallingUid()}/${Binder.getCallingPid()}"

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

    @JvmStatic
    fun getRedactedAddressStringFromByte(address: ByteArray?): String? {
        if (address == null || address.size != Utils.BD_ADDR_LEN) {
            return null
        }

        return String.format("XX:XX:XX:XX:%02X:%02X", address[4], address[5])
    }

    @JvmStatic fun BluetoothDevice.getByteAddress() = getBytesFromAddress(address)

    @JvmStatic
    fun getBytesFromAddress(address: String) =
        address.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Converts HCI disconnect reasons to Android disconnect reasons.
     *
     * The HCI Error Codes used for ACL disconnect reasons propagated up from native code were
     * copied from: packages/modules/Bluetooth/system/stack/include/hci_error_code.h
     *
     * These error codes are specified and described in Bluetooth Core Spec v5.1, Vol 2, Part D.
     *
     * @param hciReason is the raw HCI disconnect reason from native.
     * @return the Android disconnect reason for apps.
     */
    @JvmStatic
    @BluetoothAdapter.BluetoothConnectionCallback.DisconnectReason
    fun hciToAndroidDisconnectReason(hciReason: Int) =
        when (hciReason) {
            0x00 /* HCI_SUCCESS */,
            0x1F /* HCI_ERR_UNSPECIFIED */,
            0xff /* HCI_ERR_UNDEFINED */ -> {
                BluetoothStatusCodes.ERROR_UNKNOWN
            }
            0x01 /* HCI_ERR_ILLEGAL_COMMAND */,
            0x02 /* HCI_ERR_NO_CONNECTION */,
            0x03 /* HCI_ERR_HW_FAILURE */,
            0x2A /* HCI_ERR_DIFF_TRANSACTION_COLLISION */,
            0x32 /* HCI_ERR_ROLE_SWITCH_PENDING */,
            0x35 /* HCI_ERR_ROLE_SWITCH_FAILED */ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL
            0x04 /* HCI_ERR_PAGE_TIMEOUT */,
            0x08 /* HCI_ERR_CONNECTION_TOUT */,
            0x10 /* HCI_ERR_HOST_TIMEOUT */,
            0x22 /* HCI_ERR_LMP_RESPONSE_TIMEOUT */,
            0x3C /* HCI_ERR_ADVERTISING_TIMEOUT */,
            0x3E /* HCI_ERR_CONN_FAILED_ESTABLISHMENT */ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT
            0x05 /* HCI_ERR_AUTH_FAILURE */,
            0x06 /* HCI_ERR_KEY_MISSING */,
            0x0E /* HCI_ERR_HOST_REJECT_SECURITY */,
            0x17 /* HCI_ERR_REPEATED_ATTEMPTS */,
            0x18 /* HCI_ERR_PAIRING_NOT_ALLOWED */,
            0x25 /* HCI_ERR_ENCRY_MODE_NOT_ACCEPTABLE */,
            0x26 /* HCI_ERR_UNIT_KEY_USED */,
            0x29 /* HCI_ERR_PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED */,
            0x2F /* HCI_ERR_INSUFFICIENT_SECURITY */,
            0x38 /* HCI_ERR_HOST_BUSY_PAIRING */ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY
            0x07 /* HCI_ERR_MEMORY_FULL */,
            0x09 /* HCI_ERR_MAX_NUM_OF_CONNECTIONS */,
            0x0A /* HCI_ERR_MAX_NUM_OF_SCOS */,
            0x0C /* HCI_ERR_COMMAND_DISALLOWED */,
            0x0D /* HCI_ERR_HOST_REJECT_RESOURCES */,
            0x43 /* HCI_ERR_LIMIT_REACHED */ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED
            0x0B /*HCI_ERR_CONNECTION_EXISTS*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS
            0x0F /*HCI_ERR_HOST_REJECT_DEVICE*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SYSTEM_POLICY
            0x12 /*HCI_ERR_ILLEGAL_PARAMETER_FMT*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS
            0x13 /*HCI_ERR_PEER_USER*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST
            0x15 /*HCI_ERR_REMOTE_POWER_OFF*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST
            0x16 /*HCI_ERR_CONN_CAUSE_LOCAL_HOST*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL_REQUEST
            0x1A /*HCI_ERR_UNSUPPORTED_REM_FEATURE*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE
            0x3B /*HCI_ERR_UNACCEPT_CONN_INTERVAL*/ ->
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS
            else -> {
                Log.e(TAG, "Invalid HCI disconnect reason: $hciReason")
                BluetoothStatusCodes.ERROR_UNKNOWN
            }
        }

    /**
     * Check if BLE is supported by this platform
     *
     * @param context current device context
     * @return `true` if BLE is supported, `false` otherwise
     */
    @JvmStatic
    fun Context.isBleSupported() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** @return `true` if this Android device is an automotive device, `false` otherwise */
    @JvmStatic
    fun Context.isAutomotive() = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /** @return `true` if this Android device is an IoT device, `false` otherwise */
    @JvmStatic
    fun Context.isIotDevice() = packageManager.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)

    /** @return `true` if this Android device is a TV device, `false` otherwise */
    @Suppress("DEPRECATION") // Checking deprecated PackageManager.FEATURE_TELEVISION
    @JvmStatic
    fun Context.isTv() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** @return `true` if this Android device is a watch device, `false` otherwise */
    @JvmStatic fun Context.isWatch() = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    /** @return `true` if this Android device is an XR device, `false` otherwise */
    fun Context.isXrDevice() = packageManager.hasSystemFeature(PackageManager.FEATURE_XR_PERIPHERAL)

    /**
     * Returns true if the specified package has disavowed the use of bluetooth scans for location,
     * that is, if they have specified the `neverForLocation` flag on the [BLUETOOTH_SCAN]
     * permission.
     */
    @Suppress("IncorrectRequiresPermissionPropagation") // This method checks the permission
    @JvmStatic
    fun hasDisavowedLocationForScan(
        context: Context,
        source: AttributionSource,
        inTestMode: Boolean,
    ): Boolean {
        // Check every step along the attribution chain for a renouncement.
        // If location has been renounced anywhere in the chain we treat it as a disavowal.
        var currentAttrib = source
        while (true) {
            if (
                currentAttrib.renouncedPermissions.contains(ACCESS_FINE_LOCATION) &&
                    (inTestMode ||
                        (context.checkPermission(RENOUNCE_PERMISSIONS, -1, currentAttrib.uid) ==
                            PackageManager.PERMISSION_GRANTED))
            ) {
                return true
            }
            val nextAttrib = currentAttrib.next ?: break
            currentAttrib = nextAttrib
        }

        // Check the last attribution in the chain for a neverForLocation disavowal.
        val packageName = currentAttrib.packageName

        // Previous check must have enforced isSameProfileGroup(currentAttrib.uid, myUserHandle)
        val packageInfo =
            try {
                val userHandle = UserHandle.getUserHandleForUid(currentAttrib.uid)
                val contextAsUser = context.createPackageContextAsUser(packageName!!, 0, userHandle)
                contextAsUser.packageManager.getPackageInfo(packageName, GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not find package for disavowal check: $packageName")
                return false
            }

        val index = packageInfo.requestedPermissions?.indexOf(BLUETOOTH_SCAN) ?: -1
        if (index == -1) {
            return false
        }

        val flags = packageInfo.requestedPermissionsFlags?.get(index) ?: 0
        return (flags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0
    }

    /**
     * Checks CoD and metadata to determine if the remote device is a watch
     *
     * @return whether it's a watch or not
     */
    @JvmStatic
    fun remoteDeviceIsWatch(adapterService: AdapterService, device: BluetoothDevice): Boolean {
        // Check CoD
        val deviceClass = BluetoothClass(adapterService.getRemoteClass(device))
        if (deviceClass.deviceClass == BluetoothClass.Device.WEARABLE_WRIST_WATCH) {
            return true
        }

        // Check metadata
        val deviceType = adapterService.getMetadata(device, METADATA_DEVICE_TYPE) ?: return false
        return String(deviceType) == BluetoothDevice.DEVICE_TYPE_WATCH
    }

    /** Checks whether location is off and must be on for us to perform some operation */
    @JvmStatic
    fun Context.blockedByLocationOff(userHandle: UserHandle) =
        !getSystemService(LocationManager::class.java).isLocationEnabledForUser(userHandle)

    /** Checks that calling process has ACCESS_COARSE_LOCATION and OP_COARSE_LOCATION is allowed */
    @Suppress("IncorrectRequiresPermissionPropagation") // This method checks the permission
    @JvmStatic
    fun Context.checkCallerHasCoarseLocation(
        source: AttributionSource,
        userHandle: UserHandle,
    ): Boolean {
        if (blockedByLocationOff(userHandle)) {
            Log.e(TAG, "Permission denial: Location is off")
            return false
        }
        val currentAttribution =
            AttributionSource.Builder(attributionSource).setNext(source).build()
        val permissionManager = getSystemService(PermissionManager::class.java) ?: return false
        val result =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                ACCESS_COARSE_LOCATION,
                currentAttribution,
                "Bluetooth location check",
            )
        if (result == PERMISSION_GRANTED) {
            return true
        }

        Log.e(TAG, "Need ACCESS_COARSE_LOCATION permission for $currentAttribution")
        return false
    }

    /**
     * Checks that calling process has ACCESS_COARSE_LOCATION and OP_COARSE_LOCATION is allowed or
     * ACCESS_FINE_LOCATION and OP_FINE_LOCATION is allowed
     */
    @Suppress("IncorrectRequiresPermissionPropagation") // This method checks the permission
    @JvmStatic
    fun Context.checkCallerHasCoarseOrFineLocation(
        source: AttributionSource,
        userHandle: UserHandle,
    ): Boolean {
        if (blockedByLocationOff(userHandle)) {
            Log.e(TAG, "Permission denial: Location is off.")
            return false
        }

        val currentAttribution =
            AttributionSource.Builder(attributionSource).setNext(source).build()
        val permissionManager = getSystemService(PermissionManager::class.java) ?: return false
        val fineResult =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                ACCESS_FINE_LOCATION,
                currentAttribution,
                "Bluetooth location check",
            )
        if (fineResult == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        val coarseResult =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                ACCESS_COARSE_LOCATION,
                currentAttribution,
                "Bluetooth location check",
            )
        if (coarseResult == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        Log.e(
            TAG,
            "Need ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission for $currentAttribution",
        )
        return false
    }

    /** Checks that calling process has ACCESS_FINE_LOCATION and OP_FINE_LOCATION is allowed */
    @Suppress("IncorrectRequiresPermissionPropagation") // This method checks the permission
    @JvmStatic
    fun Context.checkCallerHasFineLocation(
        source: AttributionSource,
        userHandle: UserHandle,
    ): Boolean {
        if (blockedByLocationOff(userHandle)) {
            Log.e(TAG, "Permission denial: Location is off.")
            return false
        }

        val currentAttribution =
            AttributionSource.Builder(attributionSource).setNext(source).build()
        val permissionManager = getSystemService(PermissionManager::class.java) ?: return false
        val result =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                ACCESS_FINE_LOCATION,
                currentAttribution,
                "Bluetooth location check",
            )
        if (result == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        Log.e(TAG, "Need ACCESS_FINE_LOCATION permission for $currentAttribution")
        return false
    }

    /**
     * Checks that the target sdk of the app corresponding to the provided package name is greater
     * than or equal to the passed in target sdk.
     *
     * For example, if the calling app has target SDK [Build.VERSION_CODES.S] and we pass in the
     * targetSdk [Build.VERSION_CODES.R], the API will return true because S >= R.
     *
     * @param source caller's [AttributionSource]
     * @param expectedMinimumTargetSdk one of the values from [Build.VERSION_CODES]
     * @return `true` if the caller's target sdk is greater than or equal to
     *   expectedMinimumTargetSdk, `false` otherwise
     */
    @JvmStatic
    fun Context.checkCallerTargetSdk(source: AttributionSource, expectedMinimumTargetSdk: Int) =
        try {
            packageManager.getApplicationInfo(source.packageName!!, 0).targetSdkVersion >=
                expectedMinimumTargetSdk
        } catch (e: PackageManager.NameNotFoundException) {
            // In case of exception, assume true
            true
        }

    /** Returns `true` if the caller holds [NETWORK_SETTINGS] */
    @JvmStatic
    fun Context.checkCallerHasNetworkSettingsPermission() =
        checkCallerHasPermission(NETWORK_SETTINGS)

    /** Returns `true` if the caller holds [NETWORK_SETUP_WIZARD] */
    @JvmStatic
    fun Context.checkCallerHasNetworkSetupWizardPermission() =
        checkCallerHasPermission(NETWORK_SETUP_WIZARD)

    /** Returns `true` if the caller holds [RADIO_SCAN_WITHOUT_LOCATION] */
    @JvmStatic
    fun Context.checkCallerHasScanWithoutLocationPermission() =
        checkCallerHasPermission(RADIO_SCAN_WITHOUT_LOCATION)

    /** Returns `true` if the caller holds [BLUETOOTH_PRIVILEGED] */
    @JvmStatic
    fun Context.checkCallerHasPrivilegedPermission() =
        checkCallerHasPermission(BLUETOOTH_PRIVILEGED)

    /** Returns `true` if the uid / packageName pair holds [BLUETOOTH_PRIVILEGED] */
    @JvmStatic
    fun Context.checkPrivilegedPermission(packageName: String, uid: Int): Boolean {
        val app = getPackageInfoAsUser(packageName, uid)

        val permissions = app?.requestedPermissions ?: return false
        val flags = app.requestedPermissionsFlags ?: return false

        for (i in permissions.indices) {
            if (
                permissions[i] == BLUETOOTH_PRIVILEGED &&
                    (flags[i] and REQUESTED_PERMISSION_GRANTED) != 0
            ) {
                return true
            }
        }
        return false
    }

    private fun Context.getPackageInfoAsUser(packageName: String, uid: Int): PackageInfo? {
        return try {
            val user = UserHandle.getUserHandleForUid(uid)
            val pm = createContextAsUser(user, 0).packageManager
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "NameNotFoundException $packageName")
            null
        }
    }

    /** Returns `true` if the caller holds [WRITE_SMS] */
    @JvmStatic fun Context.checkCallerHasWriteSmsPermission() = checkCallerHasPermission(WRITE_SMS)

    @PermissionMethod
    private fun Context.checkCallerHasPermission(@PermissionName permission: String) =
        checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED

    @JvmStatic fun getTempBroadcastBundle() = getTempBroadcastOptions().toBundle()

    @JvmStatic
    fun getTempBroadcastOptions(): BroadcastOptions {
        val broadcastOptions = BroadcastOptions.makeBasic()
        // Use the Bluetooth process identity to pass permission check when reading DeviceConfig
        val identity = Binder.clearCallingIdentity()
        try {
            val durationMs =
                DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_BLUETOOTH,
                    KEY_TEMP_ALLOW_LIST_DURATION_MS,
                    DEFAULT_TEMP_ALLOW_LIST_DURATION_MS,
                )
            broadcastOptions.setTemporaryAppAllowlist(
                durationMs,
                PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_BLUETOOTH_BROADCAST,
                "",
            )
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return broadcastOptions
    }

    /**
     * Verifies whether the calling package name matches the calling app uid
     *
     * @param context the Bluetooth AdapterService context
     * @param callingPackage the calling application package name
     * @param callingUid the calling application uid
     * @return `true` if the package name matches the calling app uid, `false` otherwise
     */
    @JvmStatic
    fun Context.isPackageNameAccurate(callingPackage: String, callingUid: Int): Boolean {
        val header = "isPackageNameAccurate: App with package name $callingPackage"
        val callingUser = UserHandle.getUserHandleForUid(callingUid)

        // Verifies the integrity of the calling package name
        try {
            val packageUid =
                createContextAsUser(callingUser, 0).packageManager.getPackageUid(callingPackage, 0)
            if (packageUid != callingUid) {
                Log.e(TAG, "$header is UID $packageUid but caller is $callingUid")
                return false
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Log.e(TAG, "$header does not exist")
            return false
        }
        return true
    }

    /**
     * Checks if the caller to the method is system server.
     *
     * @param tag the log tag to use in case the caller is not system server
     * @param method the API method name
     * @return `true` if the caller is system server, `false` otherwise
     */
    @JvmStatic
    fun callerIsSystem(tag: String, method: String): Boolean {
        if (isInstrumentationTestMode) {
            return true
        }
        val res = checkCallerIsSystem()
        if (!res) {
            Log.w(TAG, "$tag.$method() - Not allowed outside system server")
        }
        return res
    }

    private fun checkCallerIsSystem() =
        UserHandle.getAppId(Process.SYSTEM_UID) == UserHandle.getAppId(Binder.getCallingUid())

    @JvmStatic
    fun Context.callerIsSystemOrActiveOrManagedUser(tag: String, method: String) =
        checkCallerIsSystemOrActiveOrManagedUser("$tag.$method()")

    @JvmStatic
    fun Context.checkCallerIsSystemOrActiveOrManagedUser(tag: String): Boolean {
        if (isInstrumentationTestMode) {
            return true
        }
        val res = checkCallerIsAllowed()
        if (!res) {
            Log.w(TAG, "$tag - Not allowed for non-active user and non-system and non-managed user")
        }
        return res
    }

    // Allowed caller should be:
    // * Current user
    // * Any profile in the same group of the current user (work profile, private space, clone, …)
    //
    // Then, for broader compatibility, we need to add some special situation that are miss-handling
    // the multi-user scenario:
    // * SystemUiUid because global UI is running under user 0
    // * System user in case we are in HSUM mode
    // * System uid for any request from the system server
    private fun Context.checkCallerIsAllowed(): Boolean {
        val currentUser = Process.myUserHandle()
        val callingUid = Binder.getCallingUid()
        val callingUser = UserHandle.getUserHandleForUid(callingUid)

        val identity = Binder.clearCallingIdentity()
        try {
            return currentUser == callingUser ||
                UserHandle.getAppId(Process.SYSTEM_UID) == UserHandle.getAppId(callingUid) ||
                // SystemUiUid wrongfully run and request for User 0. It needs dedicated exception
                UserHandle.getAppId(Utils.getSystemUiUid()) == UserHandle.getAppId(callingUid) ||
                // In HSUM, UserHandle.SYSTEM is only for System, not human
                (UserManager.isHeadlessSystemUserMode() && callingUser == UserHandle.SYSTEM) ||
                // Allow any users in the same group (Managed, clone, private...)
                getSystemService(UserManager::class.java)
                    .isSameProfileGroup(currentUser, callingUser) // Requires Bluetooth Identity
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * Returns `true` if the [BLUETOOTH_ADVERTISE] permission is granted for the calling app.
     * Returns `false` if the result is a soft denial. Throws [SecurityException] if the result is a
     * hard denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmStatic
    @RequiresPermission(BLUETOOTH_ADVERTISE)
    fun enforceAdvertisePermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        message: String,
    ) = enforcePermissionForDataDelivery(context, BLUETOOTH_ADVERTISE, source, message)

    /**
     * Returns `true` if the [BLUETOOTH_CONNECT] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmOverloads
    @JvmStatic
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun enforceConnectPermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        tagOrMessage: String,
        method: String? = null,
        allowPccBypass: Boolean = false,
    ): Boolean {
        if (isPccUid() && !allowPccBypass) {
            throw SecurityException("PCC UIDs are blocked by default from Bluetooth APIs.")
        }
        val message = if (method == null) tagOrMessage else "$tagOrMessage.$method()"
        return enforcePermissionForDataDelivery(context, BLUETOOTH_CONNECT, source, message)
    }

    /**
     * Returns `true` if the [BLUETOOTH_SCAN] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmStatic
    @RequiresPermission(BLUETOOTH_SCAN)
    fun enforceScanPermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        tag: String,
        method: String,
    ) = enforcePermissionForDataDelivery(context, BLUETOOTH_SCAN, source, "$tag.$method()")

    /**
     * Returns `true` if the [BLUETOOTH_CONNECT] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where the app op should not be noted.
     */
    @JvmOverloads
    @JvmStatic
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun enforceConnectPermissionForPreflight(
        context: Context,
        source: AttributionSource,
        allowPccBypass: Boolean = false,
    ): Boolean {
        if (isPccUid() && !allowPccBypass) {
            throw SecurityException("PCC UIDs are blocked by default from Bluetooth APIs.")
        }
        return enforcePermissionForPreflight(context, BLUETOOTH_CONNECT, source)
    }

    @PermissionMethod
    fun enforcePermissionForDataDelivery(
        context: Context,
        @PermissionName permission: String,
        source: AttributionSource,
        message: String?,
    ): Boolean {
        if (isInstrumentationTestMode) {
            return true
        }
        val currentAttribution =
            AttributionSource.Builder(context.attributionSource).setNext(source).build()
        val permissionManager =
            context.getSystemService(PermissionManager::class.java) ?: return false
        val result =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                permission,
                currentAttribution,
                message,
            )
        if (result == PERMISSION_GRANTED) {
            return true
        }

        val msg = "Need $permission permission for $currentAttribution: $message"
        if (result == PERMISSION_HARD_DENIED) {
            throw SecurityException(msg)
        } else {
            Log.w(TAG, msg)
            return false
        }
    }

    @PermissionMethod
    private fun enforcePermissionForPreflight(
        context: Context,
        @PermissionName permission: String,
        source: AttributionSource,
    ): Boolean {
        val permissionManager =
            context.getSystemService(PermissionManager::class.java) ?: return false
        val result = permissionManager.checkPermissionForPreflight(permission, source)
        if (result == PERMISSION_GRANTED) {
            return true
        }

        val msg = "Need $permission permission"
        if (result == PERMISSION_HARD_DENIED) {
            throw SecurityException(msg)
        } else {
            Log.w(TAG, msg)
            return false
        }
    }

    /** Checks if the calling UID is a Private Compute Core (PCC) UID. */
    @JvmStatic
    fun isPccUid(): Boolean {
        if (!android.app.privatecompute.flags.Flags.enablePccFrameworkSupport()) {
            return false
        }
        if (!SdkLevel.isAtLeastC()) {
            return false
        }
        return Process.isPrivateComputeCoreUid(Binder.getCallingUid())
    }

    /**
     * Checks if the calling UID is a Private Compute Core (PCC) UID.
     *
     * PCC UIDs are restricted from performing certain egress operations to maintain data privacy
     * boundaries.
     *
     * @param methodName the name of the method being checked, used for the exception message
     * @throws SecurityException if the caller is a PCC UID
     */
    @JvmStatic
    fun enforceCallingUidIsNotPcc(methodName: String) {
        if (isPccUid()) {
            throw SecurityException(
                "PCC UIDs are not allowed to perform Bluetooth egress operation: $methodName"
            )
        }
    }

    /** Execute a remote callback without propagating the [RemoteException] of a dead app */
    internal inline fun callbackToApp(block: () -> Unit) =
        try {
            block()
        } catch (e: RemoteException) {
            BluetoothUtils.logRemoteException(TAG, e)
        }

    /** Checks if the value is present in the array (null-safe). Convenient usage from Java. */
    @JvmStatic
    fun <T> Array<T>?.arrayContains(value: T): Boolean {
        return this?.contains(value) ?: false
    }
}

class ActionOnDeathRecipient(
    private val tag: String,
    private val message: String,
    private val action: () -> Unit,
) : IBinder.DeathRecipient {
    constructor(
        tag: String,
        message: String,
        actionRunnable: Runnable,
    ) : this(tag, message, { actionRunnable.run() })

    override fun binderDied() {
        Log.d(tag, "binderDied(): $message")
        action()
    }
}
